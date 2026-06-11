/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.core.image;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.parser.ExtractedImage;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkContentType;
import com.nageoffer.ai.ragent.rag.config.RagMultimodalProperties;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图片处理服务
 * <p>
 * 负责遍历 ExtractedImage，应用过滤策略，
 * 上传图片到对象存储，调用 VLM 生成描述，构造图片 VectorChunk。
 * 单张图片失败不阻断其他图片的处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessingService {

    private final FileStorageService fileStorageService;
    private final ImageDescriptionService imageDescriptionService;
    private final RagMultimodalProperties multimodalProperties;

    /**
     * 处理提取的图片列表，生成图片 VectorChunk
     *
     * @param images     从文档提取的图片列表
     * @param baseIndex  图片 chunk 的起始 index（接续文本 chunk 的序号）
     * @param bucketName 对象存储 bucket 名称（使用知识库 collection name）
     * @return 图片 VectorChunk 列表
     */
    public ProcessResult processImages(List<ExtractedImage> images, int baseIndex, String bucketName) {
        int success = 0;
        int failed = 0;
        int skipped = 0;
        List<VectorChunk> imageChunks = new ArrayList<>();

        Set<String> seenHashes = new HashSet<>();
        int maxImages = multimodalProperties.getMaxImagesPerDocument();
        int minSizeBytes = multimodalProperties.getImageMinSizeKb() * 1024;
        int maxImageBytes = multimodalProperties.getMaxImageBytes();

        for (ExtractedImage image : images) {
            // 数量限制
            if (success >= maxImages) {
                skipped += (images.size() - success - failed - skipped);
                break;
            }

            // 大小过滤
            if (image.data().length < minSizeBytes) {
                skipped++;
                continue;
            }

            // 超大图片跳过
            if (image.data().length > maxImageBytes) {
                skipped++;
                log.warn("图片超过大小限制({}MB)，已跳过。index={}", maxImageBytes / 1024 / 1024, image.index());
                continue;
            }

            // 按 hash 去重（二次去重，Tika 侧已做基础去重）
            String hash = DigestUtil.sha256Hex(image.data());
            if (!seenHashes.add(hash)) {
                skipped++;
                continue;
            }

            try {
                VectorChunk chunk = processSingleImage(image, baseIndex + success, bucketName, image.index());
                imageChunks.add(chunk);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("处理图片失败，index={}，原因: {}", image.index(), e.getMessage(), e);
            }
        }

        return new ProcessResult(imageChunks, success, failed, skipped);
    }

    private VectorChunk processSingleImage(ExtractedImage image, int chunkIndex, String bucketName, int originalIndex) {
        // 1. 上传图片到对象存储
        String fileName = "img_" + originalIndex + "." + extensionFromMimeType(image.mimeType());
        StoredFileDTO stored = fileStorageService.upload(bucketName, image.data(), fileName, image.mimeType());
        String imageUrl = stored.getUrl();

        // 2. 调用 VLM 生成描述
        String description = imageDescriptionService.describe(image.data(), image.mimeType());

        // 3. 构造图片 VectorChunk
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(chunkIndex)
                .contentType(ChunkContentType.IMAGE)
                .imageUrl(imageUrl)
                .imageMimeType(image.mimeType())
                .content("【图片描述】第 " + (originalIndex + 1) + " 张图：" + description)
                .metadata(Map.of(
                        "imageIndex", originalIndex,
                        "mimeType", image.mimeType()
                ))
                .build();
    }

    private String extensionFromMimeType(String mimeType) {
        if (mimeType == null) {
            return "png";
        }
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            default -> "png";
        };
    }

    /**
     * 图片处理结果
     */
    public record ProcessResult(List<VectorChunk> chunks, int success, int failed, int skipped) {
    }
}
