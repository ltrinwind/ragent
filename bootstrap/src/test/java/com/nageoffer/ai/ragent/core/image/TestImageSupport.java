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

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 多模态图片识别测试的样本图工具。
 * <p>
 * 取图优先级：
 * <ol>
 *   <li>classpath 资源 {@code /multimodal/sample.png}（真实截图/照片，最具说服力）</li>
 *   <li>工程相对路径 {@code src/test/resources/multimodal/sample.png}（IDE 运行）</li>
 *   <li>都不存在时，在内存中绘制一张带标题、坐标轴、数值标注的英文柱状图 PNG，保证开箱即跑</li>
 * </ol>
 * 合成图刻意使用英文标签，避免在无 CJK 字体的环境（如部分 WSL/CI）出现乱码方块。
 */
public final class TestImageSupport {

    private static final String CLASSPATH_RESOURCE = "/multimodal/sample.png";
    private static final Path FILE_RESOURCE = Paths.get("src/test/resources/multimodal/sample.png");

    private TestImageSupport() {
    }

    /**
     * @return 样本 PNG 的字节内容
     */
    public static byte[] samplePngBytes() {
        try (InputStream in = TestImageSupport.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignore) {
            // 回退到下一种来源
        }
        if (Files.exists(FILE_RESOURCE)) {
            try {
                return Files.readAllBytes(FILE_RESOURCE);
            } catch (IOException ignore) {
                // 回退到内存合成
            }
        }
        return synthesizeBarChartPng();
    }

    /**
     * 内存合成一张 480x320 的英文柱状图 PNG。
     * 尺寸落在 SiliconFlow/百炼 VLM 支持区间（56x56 ~ 3584x3584）内。
     */
    private static byte[] synthesizeBarChartPng() {
        int w = 480;
        int h = 320;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);

            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.drawString("Quarterly Sales Bar Chart", 110, 34);

            // 坐标轴
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(60, 270, 450, 270); // X 轴
            g.drawLine(60, 60, 60, 270);  // Y 轴

            int[] values = {120, 180, 90, 210};
            String[] labels = {"Q1", "Q2", "Q3", "Q4"};
            Color[] colors = {
                    new Color(79, 129, 189),
                    new Color(192, 80, 77),
                    new Color(155, 187, 89),
                    new Color(128, 100, 162)
            };
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            for (int i = 0; i < values.length; i++) {
                int barH = values[i];
                int x = 95 + i * 90;
                g.setColor(colors[i]);
                g.fillRect(x, 270 - barH, 50, barH);
                g.setColor(Color.BLACK);
                g.drawString(labels[i], x + 14, 288);
                g.drawString(String.valueOf(values[i]), x + 4, 264 - barH);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("合成测试样本图失败", e);
        }
    }
}
