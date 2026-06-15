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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多模态文档处理配置属性
 * <p>
 * 控制图片提取和 VLM 描述的行为参数。
 * 模型配置由 ai.vision 管理，此处仅包含业务级别的参数。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.multimodal")
public class RagMultimodalProperties {

    /**
     * 是否启用多模态图片描述功能
     */
    private boolean enabled = false;

    /**
     * 单文档最多提取图片数
     */
    private int maxImagesPerDocument = 50;

    /**
     * 最小图片大小（KB），低于此值的图片将被过滤
     */
    private int imageMinSizeKb = 5;

    /**
     * 单张图片最大字节数（默认 4MB）
     */
    private int maxImageBytes = 4194304;

    /**
     * VLM 并发调用数（V1 预留字段，后续接入并发控制）
     */
    private int vlmConcurrency = 4;

    /**
     * VLM 调用超时秒数（V1 预留字段，后续接入超时控制）
     */
    private int vlmTimeoutSeconds = 60;
}
