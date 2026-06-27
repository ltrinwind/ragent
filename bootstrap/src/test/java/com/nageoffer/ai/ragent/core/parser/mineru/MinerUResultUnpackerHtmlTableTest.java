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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockChunkConfig;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkContext;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MinerUResultUnpackerHtmlTableTest {

    /**
     * 验证 MinerU raw HTML 中的 table 会结构化为 TableBlock，非 table HTML 仍原样降级为 ParagraphBlock。
     */
    @Test
    void unpack_parsesHtmlTableAndKeepsUnsupportedHtmlFallback() {
        ParsedDocument parsed = unpack("""
                # 表格解析验证

                <table>
                  <caption>人员清单</caption>
                  <tr>
                    <th>部门</th>
                    <th colspan="2">人员</th>
                    <th>状态</th>
                  </tr>
                  <tr>
                    <td rowspan="2">研发部</td>
                    <td>张三</td>
                    <td>后端</td>
                    <td>在职</td>
                  </tr>
                  <tr>
                    <td>李四</td>
                    <td>前端</td>
                    <td>在职</td>
                  </tr>
                </table>

                <div>暂不支持的 HTML 块</div>
                """);

        assertThat(parsed.blocks()).hasSize(3);
        assertThat(parsed.blocks().get(0)).isInstanceOf(HeadingBlock.class);
        assertThat(parsed.blocks().get(1)).isInstanceOf(TableBlock.class);
        assertThat(parsed.blocks().get(2)).isInstanceOf(ParagraphBlock.class);

        TableBlock table = (TableBlock) parsed.blocks().get(1);
        assertThat(table.captionText()).isEqualTo("人员清单");
        assertThat(table.headers()).containsExactly("部门", "人员", "人员", "状态");
        assertThat(table.rows()).containsExactly(
                List.of("研发部", "张三", "后端", "在职"),
                List.of("研发部", "李四", "前端", "在职")
        );
        List<VectorChunk> tableChunks = new TableChunker().chunk(
                table,
                ChunkContext.of(
                        List.of("表格解析验证"),
                        new BlockChunkConfig(10_000, 0, 1, 15, 10)
                )
        );
        tableChunks.forEach(chunk -> System.out.printf(
                "TABLE chunk index=%s%ncontent:%n%s%nembeddingText:%n%s%nsectionContext:%n%s%n%n",
                chunk.getIndex(),
                chunk.getContent(),
                chunk.getEmbeddingText(),
                chunk.getSectionContext()
        ));
        assertThat(tableChunks).hasSize(2);
        assertThat(tableChunks)
                .extracting(VectorChunk::getBlockType)
                .containsOnly("TABLE");

        ParagraphBlock fallback = (ParagraphBlock) parsed.blocks().get(2);
        assertThat(fallback.text()).isEqualTo("<div>暂不支持的 HTML 块</div>");
    }

    /**
     * 验证 rowspan/colspan 会被展开，chunk 展示内容中也能看到补齐后的二维表。
     */
    @Test
    void unpack_expandsRowspanAndColspanInRenderedTableChunk() {
        ParsedDocument parsed = unpack("""
                # 跨格表格验证

                <table>
                  <caption>解析结果</caption>
                  <tr>
                    <th>阶段</th>
                    <th colspan="2">输入内容</th>
                    <th>结论</th>
                  </tr>
                  <tr>
                    <td rowspan="2">解析</td>
                    <td>表格</td>
                    <td>图片</td>
                    <td>通过</td>
                  </tr>
                  <tr>
                    <td colspan="2">公式和文本</td>
                    <td>通过</td>
                  </tr>
                </table>
                """);

        assertThat(parsed.blocks()).hasSize(2);
        assertThat(parsed.blocks().get(1)).isInstanceOf(TableBlock.class);

        TableBlock table = (TableBlock) parsed.blocks().get(1);
        assertThat(table.captionText()).isEqualTo("解析结果");
        assertThat(table.headers()).containsExactly("阶段", "输入内容", "输入内容", "结论");
        assertThat(table.rows()).containsExactly(
                List.of("解析", "表格", "图片", "通过"),
                List.of("解析", "公式和文本", "公式和文本", "通过")
        );

        List<VectorChunk> tableChunks = new TableChunker().chunk(
                table,
                ChunkContext.of(
                        List.of("跨格表格验证"),
                        new BlockChunkConfig(10_000, 0, 10, 15, 10)
                )
        );

        assertThat(tableChunks).hasSize(1);
        assertThat(tableChunks.get(0).getContent()).isEqualTo("""
                | 阶段 | 输入内容 | 输入内容 | 结论 |
                |---|---|---|---|
                | 解析 | 表格 | 图片 | 通过 |
                | 解析 | 公式和文本 | 公式和文本 | 通过 |""");
        assertThat(tableChunks.get(0).getEmbeddingText()).contains(
                "caption=解析结果; headers=阶段, 输入内容, 输入内容, 结论",
                "阶段: 解析; 输入内容: 公式和文本; 输入内容: 公式和文本; 结论: 通过"
        );
    }

    private static ParsedDocument unpack(String markdown) {
        MinerUResultUnpacker unpacker = new MinerUResultUnpacker(mock(FileStorageService.class), "ragent-assets");
        return unpacker.unpack(mineruZip(markdown), "表格解析验证.md", "doc-1");
    }

    private static byte[] mineruZip(String markdown) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("output.md"));
            zip.write(markdown.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build MinerU test zip", e);
        }
    }
}
