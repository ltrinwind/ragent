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

import java.util.ArrayList;
import java.util.List;

/**
 * 父子分块后处理器
 * 按 {@link VectorChunk#isParent()} 拆分父块和子块：
 * - chunksToEmbed 只包含子块（子块用于向量检索）
 * - contextChunks 包含全部 chunks（父块+子块），下游通过 isParent() 区分
 */
public class ParentChildChunkPostProcessor implements ChunkPostProcessor {

    @Override
    public ChunkPostResult process(List<VectorChunk> allChunks, IngestionContext context) {
        List<VectorChunk> children = new ArrayList<>();
        for (VectorChunk chunk : allChunks) {
            if (!chunk.isParent()) {
                children.add(chunk);
            }
        }

        return ChunkPostResult.builder()
                .chunksToEmbed(children)
                .contextChunks(allChunks)
                .summary("已分块 " + allChunks.size() + " 段（含 " + children.size() + " 段子块）")
                .build();
    }
}
