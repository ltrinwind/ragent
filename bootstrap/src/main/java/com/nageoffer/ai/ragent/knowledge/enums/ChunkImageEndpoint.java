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

package com.nageoffer.ai.ragent.knowledge.enums;

/**
 * chunk 图片代理端点路径契约。
 * <p>
 * KnowledgeChunkController 的 mapping 与 StreamChatPipeline 构造代理 URL 必须共用同一处定义，
 * 否则两端写死路径不一致会导致前端按 imageUrl 拉取图片时 404。
 * <p>
 * 路径分量以 {@code public static final String} 字面量形式持有（而非枚举实例字段）：
 * Spring {@code @GetMapping} 注解参数要求编译期常量表达式，而枚举实例字段经 getter 访问
 * 不构成常量表达式，无法直接用于注解；静态 {@code final String} 字面量拼接才是合法的注解实参。
 * 枚举在此仅作为该路径契约的集中命名空间，无实例。
 */
public enum ChunkImageEndpoint {

    ;

    /** chunk 图片代理端点路径前缀 */
    public static final String PATH_PREFIX = "/knowledge-base/chunks/";

    /** chunk 图片代理端点路径后缀 */
    public static final String PATH_SUFFIX = "/image";

    /**
     * 根据 chunk id 拼接图片代理端点路径。
     * <p>
     * StreamChatPipeline 构造 {@code RetrievedContextItem.imageUrl} 时调用。
     *
     * @param chunkId chunk 分布式 ID
     * @return 代理路径，如 {@code /knowledge-base/chunks/123456/image}
     */
    public static String buildPath(String chunkId) {
        return PATH_PREFIX + chunkId + PATH_SUFFIX;
    }
}
