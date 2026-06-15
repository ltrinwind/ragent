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

import com.nageoffer.ai.ragent.infra.model.ModelTarget;

/**
 * 视觉理解客户端接口
 * 用于通过视觉语言模型（VLM）对图片进行理解和描述
 */
public interface VisionClient {

    /**
     * 获取视觉服务提供商名称
     *
     * @return 提供商标识字符串
     */
    String provider();

    /**
     * 使用视觉模型对图片进行描述
     *
     * @param request 视觉请求（包含 base64 图片和 prompt）
     * @param target  目标模型配置
     * @return 模型生成的图片描述文本
     */
    String describe(VisionRequest request, ModelTarget target);
}
