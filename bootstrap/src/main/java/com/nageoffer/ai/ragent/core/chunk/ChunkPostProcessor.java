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
 * 分块后处理器接口
 * 不同分块策略可能需要不同的后处理逻辑（如父子分块需要拆分父/子块）
 * 由 {@link ChunkingMode} 枚举声明各策略对应的后处理器
 */
public interface ChunkPostProcessor {

    /**
     * 对分块结果进行后处理
     *
     * @param allChunks 分块器产出的全部 chunks
     * @param context   摄取上下文（用于桥接数据到下游节点，如父块传递）
     * @return 后处理结果
     */
    ChunkPostResult process(List<VectorChunk> allChunks, IngestionContext context);
}
