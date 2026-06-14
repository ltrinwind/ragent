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

package com.nageoffer.ai.ragent.knowledge.service;

/**
 * chunk 图片代理端点解析出的可服务图片资源（service → controller 内部契约）。
 * <p>
 * 由 {@link KnowledgeChunkService#resolveImage(String)} 返回，承载 controller 写响应所需的全部决策结果，
 * 使 service 保持无 servlet / 无 IO 依赖，业务逻辑（查询 / 校验 / MIME 兜底）集中在 service。
 *
 * @param imageUrl 对象存储内部地址（s3://），由 controller 经 FileStorageService 拉取，不外泄给前端
 * @param mimeType 响应 Content-Type（已做 {@code image/png} 兜底）
 * @param filename Content-Disposition 文件名
 */
public record ChunkImageResource(String imageUrl, String mimeType, String filename) {
}
