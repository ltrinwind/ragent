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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推送给前端「参考来源」面板的结构化检索上下文条目。
 * <p>
 * 对应一次检索命中的单条 chunk，按命中顺序与文本条目交错展示。
 * 是 StreamCallback#onContext 推送、SSE context 事件载荷、
 * 会话消息持久化（t_message.contexts JSON 列）三者共用的统一结构。
 * <p>
 * 关键约束：
 * <ul>
 *   <li>{@link #imageUrl} 是图片代理端点路径（如 {@code /knowledge-base/chunks/{id}/image}），
 *       由前端拼接 API_BASE 后按需经代理端点拉取，<b>绝不</b>暴露对象存储内部地址（s3://）。</li>
 *   <li>本对象不携带任何图片字节 / base64，图片始终留在对象存储中。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrievedContextItem {

    /**
     * 命中 chunk 的唯一标识（分布式 ID）。
     * 同时作为图片代理端点的入参，是「检索 → 前端 → 代理端点 → DB → 对象存储」的唯一稳定纽带。
     */
    private String id;

    /**
     * 文本内容；contentType 为 IMAGE 时为图片描述文本。
     */
    private String text;

    /**
     * 内容类型：{@code TEXT} 或 {@code IMAGE}。
     */
    private String contentType;

    /**
     * 图片代理路径（仅 {@code IMAGE} 类型有效），前端拼接 API_BASE 后请求。
     */
    private String imageUrl;

    /**
     * 相关性得分（预留，前端可展示）。
     */
    private Float score;
}
