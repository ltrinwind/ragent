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

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkContentType;
import com.nageoffer.ai.ragent.rag.config.RagMultimodalProperties;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片 chunk 增强服务。
 * <p>
 * 统一供 chunk 模式和 pipeline 模式在 embedding 前调用：保留图片资产引用，并在可读取图片字节时调用 VLM
 * 生成描述写入 embeddingText。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalChunkEnrichmentService {

    private final FileStorageService fileStorageService;
    private final ImageDescriptionService imageDescriptionService;
    private final RagMultimodalProperties properties;

    public void enrich(List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (VectorChunk chunk : chunks) {
            if (!isImageChunk(chunk)) {
                continue;
            }
            chunk.setContentType(ChunkContentType.IMAGE);
            enrichImageChunk(chunk);
        }
    }

    private void enrichImageChunk(VectorChunk chunk) {
        AssetRef asset = firstAsset(chunk);
        String caption = extractCaption(chunk.getContent());
        String mimeType = resolveMimeType(chunk, asset);
        String imageUrl = resolveImageUrl(chunk, asset);

        if (StringUtils.hasText(imageUrl) && !StringUtils.hasText(chunk.getImageUrl())) {
            chunk.setImageUrl(imageUrl);
        }
        if (StringUtils.hasText(mimeType) && !StringUtils.hasText(chunk.getImageMimeType())) {
            chunk.setImageMimeType(mimeType);
        }

        List<String> embeddingParts = new ArrayList<>();
        if (StringUtils.hasText(caption)) {
            embeddingParts.add(caption);
        }
        if (StringUtils.hasText(chunk.getSectionContext())) {
            embeddingParts.add(chunk.getSectionContext());
        }

        String description = existingDescription(chunk);
        if (!StringUtils.hasText(description)) {
            description = describeIfPossible(imageUrl, mimeType);
        }
        if (StringUtils.hasText(description)) {
            embeddingParts.add(description);
            Map<String, Object> metadata = chunk.getMetadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(chunk.getMetadata());
            metadata.put("imageDescription", description);
            chunk.setMetadata(metadata);
        }

        if (!embeddingParts.isEmpty()) {
            chunk.setEmbeddingText(String.join("\n", embeddingParts));
        }
    }

    private String describeIfPossible(String imageUrl, String mimeType) {
        if (!properties.isEnabled() || !StringUtils.hasText(imageUrl)) {
            return null;
        }
        try (InputStream inputStream = fileStorageService.openStream(imageUrl)) {
            byte[] bytes = inputStream.readAllBytes();
            if (bytes.length == 0 || bytes.length > properties.getMaxImageBytes()) {
                return null;
            }
            return imageDescriptionService.describe(bytes, StringUtils.hasText(mimeType) ? mimeType : "image/png");
        } catch (Exception ex) {
            log.debug("图片 chunk 描述生成跳过，url={}, reason={}", imageUrl, ex.getMessage());
            return null;
        }
    }

    private boolean isImageChunk(VectorChunk chunk) {
        if (chunk == null) {
            return false;
        }
        return ChunkContentType.IMAGE == chunk.getContentType()
                || "IMAGE".equalsIgnoreCase(chunk.getBlockType())
                || (chunk.getAssets() != null && !chunk.getAssets().isEmpty());
    }

    private AssetRef firstAsset(VectorChunk chunk) {
        return chunk.getAssets() == null || chunk.getAssets().isEmpty() ? null : chunk.getAssets().get(0);
    }

    private String resolveImageUrl(VectorChunk chunk, AssetRef asset) {
        if (StringUtils.hasText(chunk.getImageUrl())) {
            return chunk.getImageUrl();
        }
        if (asset == null) {
            return null;
        }
        if (StringUtils.hasText(asset.storageUrl())) {
            return asset.storageUrl();
        }
        if (StringUtils.hasText(asset.publicUrl())) {
            return asset.publicUrl();
        }
        return asset.originalUrl();
    }

    private String resolveMimeType(VectorChunk chunk, AssetRef asset) {
        if (StringUtils.hasText(chunk.getImageMimeType())) {
            return chunk.getImageMimeType();
        }
        return asset == null ? null : asset.mime();
    }

    private String existingDescription(VectorChunk chunk) {
        if (chunk.getMetadata() == null) {
            return null;
        }
        Object value = chunk.getMetadata().get("imageDescription");
        return value == null ? null : value.toString();
    }

    private String extractCaption(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        int start = content.indexOf("![");
        if (start < 0) {
            return content;
        }
        int end = content.indexOf(']', start + 2);
        if (end <= start + 2) {
            return "";
        }
        return content.substring(start + 2, end);
    }
}
