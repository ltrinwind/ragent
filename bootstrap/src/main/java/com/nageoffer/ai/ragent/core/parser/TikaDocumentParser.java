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

import cn.hutool.crypto.digest.DigestUtil;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Apache Tika 文档解析器
 * <p>
 * 支持多种文档格式：PDF、Word、Excel、PPT、HTML、XML 等
 * 使用 Apache Tika 库进行文档解析和文本提取
 * <p>
 * 使用底层 Tika Parser 同时提取文本和嵌入图片，再转换为结构化段落 blocks。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    /**
     * 内置安全限制：跳过小于 1KB 的图片（通常是图标/装饰）
     * 业务级别的过滤由 ImageProcessingService 负责
     */
    private static final int SAFE_MIN_IMAGE_BYTES = 1024;

    /**
     * 内置安全限制：单文档最多提取图片数
     * 业务级别的限制由 ImageProcessingService 负责
     */
    private static final int SAFE_MAX_IMAGES = 100;

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    /**
     * 结构化解析：文本按空行分段输出 ParagraphBlock，并把 Tika 提取到的图片放入 metadata。
     */
    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        ParseResult result = parse(content, mimeType, options);

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        List<Block> blocks = new ArrayList<>();
        for (String segment : result.text().split("\\n{2,}")) {
            String trimmed = segment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            blocks.add(new ParagraphBlock(UUID.randomUUID().toString(), prov, List.of(), trimmed));
        }
        return ParsedDocument.of(blocks, Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "extractedImages", result.images()
        ));
    }

    /**
     * 解析文档，同时提取文本和嵌入图片。
     */
    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }
        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);

            ParseContext parseContext = new ParseContext();
            parseContext.set(Parser.class, parser);

            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(true);
            pdfConfig.setExtractUniqueInlineImagesOnly(true);
            parseContext.set(PDFParserConfig.class, pdfConfig);

            ImageCollectingExtractor imageExtractor = new ImageCollectingExtractor(parseContext);
            parseContext.set(EmbeddedDocumentExtractor.class, imageExtractor);

            parser.parse(is, handler, new Metadata(), parseContext);

            String cleaned = TextCleanupUtil.cleanup(handler.toString());
            return ParseResult.of(cleaned, Map.of("parser", getParserType()), imageExtractor.getCollectedImages());
        } catch (Exception e) {
            log.error("Tika 解析失败，MIME 类型: {}", mimeType, e);
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }
    }

    /**
     * 仅提取文本内容。
     */
    @Override
    public String extractText(InputStream stream, String fileName) {
        try {
            String text = TIKA.parseToString(stream);
            return TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("从文件中提取文本内容失败: {}", fileName, e);
            throw new ServiceException("解析文件失败: " + fileName);
        }
    }

    private String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("text/markdown") || lower.startsWith("text/x-markdown")) {
            return false;
        }
        if (lower.startsWith("text/")) {
            return true;
        }
        return lower.contains("pdf")
                || lower.contains("word")
                || lower.contains("msword")
                || lower.contains("presentation")
                || lower.contains("powerpoint")
                || lower.equals("application/json")
                || lower.equals("application/xml")
                || lower.equals("application/xhtml+xml")
                || lower.equals("application/rtf");
    }

    /**
     * 自定义嵌入式文档提取器，收集文档中的嵌入图片
     */
    private static class ImageCollectingExtractor extends ParsingEmbeddedDocumentExtractor {

        private final List<ExtractedImage> collectedImages = new ArrayList<>();
        private final Set<String> seenHashes = new HashSet<>();
        private int imageIndex = 0;

        public ImageCollectingExtractor(ParseContext context) {
            super(context);
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            String type = metadata.get(Metadata.CONTENT_TYPE);
            return type != null && type.startsWith("image/");
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml)
                throws IOException, SAXException {
            String mimeType = metadata.get(Metadata.CONTENT_TYPE);

            // 如果不是图片类型，跳过（交给父类处理文本嵌入）
            if (mimeType == null || !mimeType.startsWith("image/")) {
                super.parseEmbedded(stream, handler, metadata, outputHtml);
                return;
            }

            // 安全限制：超出最大数量则跳过
            if (collectedImages.size() >= SAFE_MAX_IMAGES) {
                return;
            }

            // 读取图片字节
            byte[] imageData = readAllBytes(stream);

            // 安全过滤：跳过过小的图片（通常是图标/装饰）
            if (imageData.length < SAFE_MIN_IMAGE_BYTES) {
                return;
            }

            // 按 hash 去重
            String hash = DigestUtil.sha256Hex(imageData);
            if (!seenHashes.add(hash)) {
                return;
            }

            collectedImages.add(new ExtractedImage(imageIndex++, imageData, mimeType));
        }

        private byte[] readAllBytes(InputStream stream) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int nRead;
            while ((nRead = stream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        }

        public List<ExtractedImage> getCollectedImages() {
            return collectedImages;
        }
    }
}
