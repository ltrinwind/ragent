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

import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;

import java.util.List;

/**
 * 默认分块后处理器
 * 适用于 FIXED_SIZE、RECURSIVE、STRUCTURE_AWARE 等扁平分块策略
 * 所有 chunks 统一嵌入，全部放入 context
 */
public class DefaultChunkPostProcessor implements ChunkPostProcessor {

    @Override
    public ChunkPostResult process(List<VectorChunk> allChunks, IngestionContext context) {
        return ChunkPostResult.builder()
                .chunksToEmbed(allChunks)
                .contextChunks(allChunks)
                .summary("已分块 " + allChunks.size() + " 段")
                .build();
    }
}
