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

package com.nageoffer.ai.ragent.core.parser.image;

import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 为 parser 产出的 ImageBlock 补齐 VLM 描述，再交给 block-aware chunker 分发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageBlockDescriptionEnricher {

    private static final String S3_URL_PREFIX = "s3://";

    private final FileStorageService fileStorageService;
    private final VlmService vlmService;
    private final ImageParseProperties properties;

    public ParsedDocument enrich(ParsedDocument parsed) {
        if (parsed == null || parsed.blocks() == null || parsed.blocks().isEmpty()) {
            return parsed;
        }

        List<Block> enrichedBlocks = new ArrayList<>(parsed.blocks().size());
        boolean changed = false;
        for (Block block : parsed.blocks()) {
            if (block instanceof ImageBlock imageBlock) {
                ImageBlock enriched = enrichImageBlock(imageBlock);
                enrichedBlocks.add(enriched);
                changed = changed || enriched != imageBlock;
            } else {
                enrichedBlocks.add(block);
            }
        }
        return changed ? ParsedDocument.of(enrichedBlocks, parsed.metadata()) : parsed;
    }

    private ImageBlock enrichImageBlock(ImageBlock block) {
        if (StringUtils.hasText(block.description()) || block.asset() == null) {
            return block;
        }

        String storageUrl = block.asset().storageUrl();
        if (!StringUtils.hasText(storageUrl) || !storageUrl.startsWith(S3_URL_PREFIX)) {
            log.debug("跳过非内部存储图片描述生成: blockId={}, url={}", block.id(), storageUrl);
            return block;
        }

        byte[] imageBytes = readImageBytes(storageUrl, block.id());
        String mime = StringUtils.hasText(block.asset().mime()) ? block.asset().mime() : "image/png";
        String description = vlmService.describeImage(
                imageBytes, mime, properties.getDescriptionPrompt(), properties.getMaxOutputTokens());
        description = description == null ? "" : description.strip();
        if (description.isBlank()) {
            throw new ServiceException("VLM 返回空描述，无法生成可检索图片文本：blockId=" + block.id());
        }

        return new ImageBlock(
                block.id(),
                block.provenance(),
                block.outlinePath(),
                block.asset(),
                block.caption(),
                block.altText(),
                description
        );
    }

    private byte[] readImageBytes(String storageUrl, String blockId) {
        try (InputStream input = fileStorageService.openStream(storageUrl)) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0) {
                throw new ServiceException("图片资产为空，无法生成描述：blockId=" + blockId);
            }
            return bytes;
        } catch (IOException e) {
            throw new ServiceException("读取图片资产失败，无法生成描述：blockId=" + blockId + ", " + e.getMessage());
        }
    }
}
