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

import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageBlockDescriptionEnricherTest {

    @Test
    void enrichesImageBlocksWithVlmDescriptionBeforeChunkDispatch() {
        byte[] imageBytes = new byte[] {1, 2, 3};
        FileStorageService fileStorageService = mock(FileStorageService.class);
        VlmService vlmService = mock(VlmService.class);
        ImageParseProperties properties = new ImageParseProperties();
        properties.setDescriptionPrompt("describe image");
        properties.setMaxOutputTokens(128);

        when(fileStorageService.openStream("s3://assets/chart.png"))
                .thenReturn(new ByteArrayInputStream(imageBytes));
        when(vlmService.describeImage(any(byte[].class), eq("image/png"), eq("describe image"), eq(128)))
                .thenReturn("这是一张季度销售趋势图。");

        ParagraphBlock paragraph = new ParagraphBlock(
                "p1", Provenance.ofFile("report.pdf"), List.of(), "正文");
        ImageBlock image = new ImageBlock(
                "img1",
                Provenance.ofFile("report.pdf"),
                List.of("销售"),
                new AssetRef(
                        "s3://assets/chart.png",
                        "https://assets.example/chart.png",
                        "images/chart.png",
                        "image/png",
                        "img1"),
                "季度销售图",
                "季度销售图");
        ParsedDocument parsed = ParsedDocument.of(List.of(paragraph, image), Map.of("parser", "MinerU"));

        ImageBlockDescriptionEnricher enricher = new ImageBlockDescriptionEnricher(
                fileStorageService, vlmService, properties);

        ParsedDocument enriched = enricher.enrich(parsed);

        assertThat(enriched.metadata()).containsEntry("parser", "MinerU");
        assertThat(enriched.blocks()).hasSize(2);
        assertThat(enriched.blocks().get(0)).isSameAs(paragraph);
        Block enrichedImage = enriched.blocks().get(1);
        assertThat(enrichedImage).isInstanceOf(ImageBlock.class);
        assertThat(((ImageBlock) enrichedImage).description()).isEqualTo("这是一张季度销售趋势图。");
        assertThat(((ImageBlock) enrichedImage).asset()).isEqualTo(image.asset());
        verify(fileStorageService).openStream("s3://assets/chart.png");
    }
}
