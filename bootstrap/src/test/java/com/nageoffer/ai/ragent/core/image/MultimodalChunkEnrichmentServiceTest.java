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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultimodalChunkEnrichmentServiceTest {

    @Test
    void enrich_addsVlmDescriptionToImageChunkEmbeddingTextAndKeepsAssetReference() {
        byte[] imageBytes = TestImageSupport.samplePngBytes();
        FileStorageService fileStorageService = mock(FileStorageService.class);
        ImageDescriptionService imageDescriptionService = mock(ImageDescriptionService.class);
        RagMultimodalProperties properties = new RagMultimodalProperties();
        properties.setEnabled(true);

        when(fileStorageService.openStream("s3://knowledge/images/chart.png"))
                .thenReturn(new ByteArrayInputStream(imageBytes));
        when(imageDescriptionService.describe(any(byte[].class), eq("image/png")))
                .thenReturn("图片展示 2024 年 Q1 到 Q4 销售额持续增长，Q4 最高。");

        VectorChunk imageChunk = VectorChunk.builder()
                .chunkId("chunk-1")
                .index(0)
                .content("![季度销售图](https://assets.example/chart.png)")
                .contentType(ChunkContentType.IMAGE)
                .imageUrl("s3://knowledge/images/chart.png")
                .imageMimeType("image/png")
                .blockType("IMAGE")
                .assets(List.of(new AssetRef(
                        "s3://knowledge/images/chart.png",
                        "https://assets.example/chart.png",
                        "https://example.com/chart.png",
                        "image/png",
                        "block-1"
                )))
                .build();

        MultimodalChunkEnrichmentService service = new MultimodalChunkEnrichmentService(
                fileStorageService,
                imageDescriptionService,
                properties
        );

        service.enrich(List.of(imageChunk));

        assertThat(imageChunk.getContentType()).isEqualTo(ChunkContentType.IMAGE);
        assertThat(imageChunk.getImageUrl()).isEqualTo("s3://knowledge/images/chart.png");
        assertThat(imageChunk.getAssets()).hasSize(1);
        assertThat(imageChunk.getEmbeddingText())
                .contains("季度销售图")
                .contains("Q1 到 Q4")
                .contains("Q4 最高");
        assertThat(imageChunk.getMetadata())
                .containsEntry("imageDescription", "图片展示 2024 年 Q1 到 Q4 销售额持续增长，Q4 最高。");
    }
}
