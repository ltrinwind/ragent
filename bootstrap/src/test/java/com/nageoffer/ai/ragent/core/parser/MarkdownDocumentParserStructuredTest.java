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

package com.nageoffer.ai.ragent.core.parser;

import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDocumentParserStructuredTest {

    @Test
    void parseStructured_promotesMarkdownImageLinksToImageBlocks() {
        String markdown = """
                # 产品架构

                下图展示整体链路。

                ![RAG 架构图](https://example.com/rag-arch.png)
                """;

        MarkdownDocumentParser parser = new MarkdownDocumentParser();
        ParsedDocument parsed = parser.parseStructured(
                markdown.getBytes(StandardCharsets.UTF_8),
                "text/markdown",
                Map.of("sourceFile", "guide.md")
        );

        assertThat(parsed.blocks())
                .extracting(Block::getClass)
                .containsExactly(HeadingBlock.class, ParagraphBlock.class, ImageBlock.class);

        ImageBlock image = (ImageBlock) parsed.blocks().get(2);
        assertThat(image.altText()).isEqualTo("RAG 架构图");
        assertThat(image.caption()).isEqualTo("RAG 架构图");
        assertThat(image.asset()).isNotNull();
        assertThat(image.asset().originalUrl()).isEqualTo("https://example.com/rag-arch.png");
        assertThat(image.provenance().sourceFile()).isEqualTo("guide.md");
    }
}
