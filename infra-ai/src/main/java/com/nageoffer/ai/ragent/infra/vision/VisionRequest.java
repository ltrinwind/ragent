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

package com.nageoffer.ai.ragent.infra.vision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视觉模型请求 DTO
 * 封装发送给 VLM 的请求参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionRequest {

    /**
     * 提示词（描述指令）
     */
    private String prompt;

    /**
     * 图片的 base64 data URI
     * 格式：data:{mimeType};base64,{payload}
     */
    private String base64DataUri;

    /**
     * 生成温度（可选）
     */
    private Double temperature;

    /**
     * 最大生成 token 数（可选）
     */
    private Integer maxTokens;
}
