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

package com.nageoffer.ai.ragent.knowledge.controller.request;

import lombok.Data;

/**
 * 知识库 Chunk 创建请求
 */
@Data
public class KnowledgeChunkCreateRequest {

    /**
     * 分块正文内容
     */
    private String content;

    /**
     * 下标
     */
    private Integer index;

    /**
     * 分块 ID
     */
    private String chunkId;

    /**
     * 父块ID（父子分块模式下，子块指向其所属父块）
     * 普通分块模式下为 null
     */
    private String parentId;

    /**
     * 内容类型：TEXT 或 IMAGE
     */
    private String contentType;

    /**
     * 图片对象存储地址（仅 IMAGE 类型有效）
     */
    private String imageUrl;

    /**
     * 图片 MIME 类型（仅 IMAGE 类型有效）
     */
    private String imageMimeType;
}
