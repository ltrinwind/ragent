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

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 多模态流水线测试用的 PDF 生成工具。
 * <p>
 * 用 PDFBox 3.0.5（Tika 传递依赖，已在 bootstrap classpath）在内存中合成一张
 * <b>包含真实文本 + 嵌入柱状图</b> 的 PDF，作为 {@link TikaDocumentParser} 的输入，
 * 用来验证 Pipeline 模式下"解析文本 + 提取嵌入图片"这一步是否可行。
 * <p>
 * 文本刻意用英文：PDFBox 的 Type1 标准字体（Helvetica）不支持 CJK，强行写中文会丢字。
 * 嵌入图片来自 {@link com.nageoffer.ai.ragent.core.image.TestImageSupport#samplePngBytes()}。
 */
public final class TestPdfSupport {

    private TestPdfSupport() {
    }

    /**
     * 生成一张 A4 PDF：顶部两行英文文本，下方嵌入一张柱状图 PNG。
     */
    public static byte[] pdfWithTextAndChartImage() throws IOException {
        byte[] chartPng = com.nageoffer.ai.ragent.core.image.TestImageSupport.samplePngBytes();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDImageXObject image = PDImageXObject.createFromByteArray(doc, chartPng, "chart.png");

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                cs.newLineAtOffset(80, 760);
                cs.showText("Quarterly Sales Report");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(80, 735);
                cs.showText("The chart below shows sales from Q1 to Q4 in year 2024.");
                cs.endText();

                // 嵌入图片：x=80, y=420, 宽 360, 高 240（PDF 坐标系原点在左下角）
                cs.drawImage(image, 80, 420, 360, 240);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
