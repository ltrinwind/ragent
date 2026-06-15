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

/**
 * 视觉理解服务接口
 * <p>
 * 提供图片描述能力，自动选择可用的视觉模型并进行降级处理
 * 支持自动路由和指定模型两种调用方式
 */
public interface VisionService {

    /**
     * 使用默认模型对图片进行描述（自动路由 + 降级）
     *
     * @param request 视觉请求
     * @return 图片描述文本
     */
    String describe(VisionRequest request);

    /**
     * 使用指定模型对图片进行描述
     *
     * @param request 视觉请求
     * @param modelId 指定的模型ID
     * @return 图片描述文本
     */
    String describe(VisionRequest request, String modelId);
}
