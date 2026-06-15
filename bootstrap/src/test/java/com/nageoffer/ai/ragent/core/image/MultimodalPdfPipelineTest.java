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

import com.nageoffer.ai.ragent.core.parser.ExtractedImage;
import com.nageoffer.ai.ragent.core.parser.ParseResult;
import com.nageoffer.ai.ragent.core.parser.TestPdfSupport;
import com.nageoffer.ai.ragent.core.parser.TikaDocumentParser;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.vision.RoutingVisionService;
import com.nageoffer.ai.ragent.infra.vision.SiliconFlowVisionClient;
import com.nageoffer.ai.ragent.infra.vision.VisionClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 多模态图片识别端到端可行性测试 —— 用一张"含文本 + 嵌入图片"的真实 PDF 走完整链路：
 * <pre>
 *   PDF（文本+图片） --TikaDocumentParser--> ParseResult(text, images)
 *                                     |
 *                                     +-- images[0] --ImageDescriptionService--> VLM 中文描述
 * </pre>
 * <p>
 * 验证两步：
 * <ol>
 *   <li>{@link #tika_extracts_text_and_image_from_pdf()}：Tika 能从 PDF 中同时解析出文本与嵌入图片（离线，始终运行）。</li>
 *   <li>{@link #vlm_describes_image_extracted_by_tika()}：把 Tika 提取到的图片交给 VLM，拿到中文描述（需 BAILIAN_API_KEY，否则跳过）。</li>
 * </ol>
 * 这两步若都通过，后续分块 / 向量入库 / 检索基本不会再出问题。
 * <p>
 * 整条链路纯 Java 手工装配，不启动 Spring / DB / MQ / 对象存储，把"图片识别"从入库流水线里彻底孤立出来。
 */
class MultimodalPdfPipelineTest {

    private static final String API_KEY = System.getenv("SILICONFLOW_API_KEY");
    private static final String PROVIDER = "siliconflow";
    private static final String PROVIDER_URL = "https://api.siliconflow.cn";
    private static final String VISION_ENDPOINT = "/v1/chat/completions";
    private static final String MODEL_ID = "Qwen/Qwen3-VL-8B-Instruct";

    /**
     * 第一步：Tika 解析 PDF，断言同时拿到文本和嵌入图片。
     * 这是 plan 阶段三（Tika 图片提取）的核心验证点，离线运行、无外部依赖。
     */
    @Test
    void tika_extracts_text_and_image_from_pdf() throws Exception {
        byte[] pdf = TestPdfSupport.pdfWithTextAndChartImage();

        TikaDocumentParser parser = new TikaDocumentParser();
        ParseResult result = parser.parse(pdf, "application/pdf", Map.of());

        System.out.println("===== Tika 解析文本 =====");
        System.out.println(result.text());
        System.out.println("===== Tika 提取图片数：" + result.images().size() + " =====");

        // 文本被正确解析
        assertThat(result.text()).contains("Quarterly Sales Report");
        assertThat(result.text()).contains("Q1 to Q4");

        // 嵌入图片被正确提取
        assertThat(result.images()).as("Tika 应至少提取到 1 张嵌入图片").isNotEmpty();
        ExtractedImage image = result.images().get(0);
        assertThat(image.data()).as("提取的图片字节不应为空").isNotEmpty();
        assertThat(image.mimeType()).as("图片 MIME 应为 image/*").startsWith("image/");
        System.out.println("首张图片 MIME=" + image.mimeType() + "，字节数=" + image.data().length);
    }

    /**
     * 第二步：把 Tika 提取到的图片交给 VLM，断言拿到非空中文描述。
     * 需要 SILICONFLOW_API_KEY，未设置时跳过，避免污染 CI 与无效计费。
     */
    @Test
    void vlm_describes_image_extracted_by_tika() throws Exception {
        assumeTrue(StringUtils.hasText(API_KEY),
                "未设置 SILICONFLOW_API_KEY 环境变量，跳过 VLM 真实联调步骤");

        byte[] pdf = TestPdfSupport.pdfWithTextAndChartImage();
        TikaDocumentParser parser = new TikaDocumentParser();
        ParseResult result = parser.parse(pdf, "application/pdf", Map.of());
        assumeTrue(!result.images().isEmpty(),
                "Tika 未提取到图片，跳过 VLM 步骤（请先确认 tika_extracts_text_and_image_from_pdf 通过）");

        ImageDescriptionService imageDescriptionService = wireVisionChain();
        ExtractedImage image = result.images().get(0);
        String description = imageDescriptionService.describe(image.data(), image.mimeType());

        System.out.println("===== VLM 对 Tika 提取图片的描述 =====");
        System.out.println(description);
        System.out.println("===================================");

        assertThat(description).as("VLM 应返回非空描述").isNotBlank();
        assertThat(description.length()).as("VLM 描述应有足够内容").isGreaterThan(10);
    }

    /**
     * 手工装配视觉链路：AIModelProperties → ModelHealthStore → Selector + Executor
     * → RoutingVisionService(BailianVisionClient) → ImageDescriptionService。
     */
    private static ImageDescriptionService wireVisionChain() {
        AIModelProperties properties = new AIModelProperties();

        AIModelProperties.ProviderConfig bailian = new AIModelProperties.ProviderConfig();
        bailian.setUrl(PROVIDER_URL);
        bailian.setApiKey(API_KEY);
        bailian.setEndpoints(Map.of("vision", VISION_ENDPOINT));
        properties.setProviders(Map.of(PROVIDER, bailian));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(MODEL_ID);
        candidate.setProvider(PROVIDER);
        candidate.setModel(MODEL_ID);
        candidate.setPriority(1);
        properties.getVision().setDefaultModel(MODEL_ID);
        properties.getVision().setCandidates(List.of(candidate));

        ModelHealthStore healthStore = new ModelHealthStore(properties);
        ModelSelector selector = new ModelSelector(properties, healthStore);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(60))
                .build();
        VisionClient visionClient = new SiliconFlowVisionClient(httpClient);
        RoutingVisionService visionService =
                new RoutingVisionService(selector, executor, List.of(visionClient));

        return new ImageDescriptionService(visionService);
    }
}
