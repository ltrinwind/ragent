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

package com.nageoffer.ai.ragent.core.chunk;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分块后处理结果
 * 封装后处理器产出的"需要嵌入的 chunks"、"放入 context 的 chunks"以及"结果描述"
 */
@Data
@Builder
public class ChunkPostResult {

    /**
     * 需要调用嵌入服务的 chunks
     * 对于父子策略，只有子块需要嵌入
     */
    private List<VectorChunk> chunksToEmbed;

    /**
     * 放入 context.chunks 的 chunks
     * 通常与 chunksToEmbed 相同，但不强制一致
     */
    private List<VectorChunk> contextChunks;

    /**
     * 结果描述信息
     */
    private String summary;
}
