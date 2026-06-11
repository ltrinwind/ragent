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

import com.nageoffer.ai.ragent.infra.vision.VisionRequest;
import com.nageoffer.ai.ragent.infra.vision.VisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 图片描述服务
 * <p>
 * 调用视觉语言模型（VLM）对图片进行理解和描述。
 * 依赖 RoutingVisionService，自动享受路由、熔断和降级能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDescriptionService {

    private static final String DEFAULT_PROMPT =
            "请用中文描述图片中的关键信息。若是图表，请提取标题、坐标轴、图例、关键数值、趋势、异常点和结论。不要编造图片中不存在的数据。";

    private final VisionService visionService;

    /**
     * 使用 VLM 对图片生成文本描述
     *
     * @param imageData 图片二进制数据
     * @param mimeType  图片 MIME 类型（如 image/png）
     * @return VLM 生成的图片描述文本
     */
    public String describe(byte[] imageData, String mimeType) {
        String dataUri = "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(imageData);

        VisionRequest request = VisionRequest.builder()
                .prompt(DEFAULT_PROMPT)
                .base64DataUri(dataUri)
                .temperature(0.3)
                .build();

        return visionService.describe(request);
    }
}
