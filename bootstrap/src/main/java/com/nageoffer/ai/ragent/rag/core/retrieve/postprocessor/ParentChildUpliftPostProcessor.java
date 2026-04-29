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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.service.ParentChunkService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 父子分块上溯后置处理器
 * <p>
 * 当检索命中的是子块时，自动上溯到父块获取更完整的上下文：
 * 1. 通过 chunk ID 查询 t_knowledge_chunk 获取 parent_id
 * 2. 如果 parent_id 不为空，从 t_knowledge_chunk_parent 取父块全文
 * 3. 将子块的 text 替换为父块的全文，保留原始得分
 * <p>
 * 非父子分块的 chunk（parent_id 为 null）不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentChildUpliftPostProcessor implements SearchResultPostProcessor {

    private final KnowledgeChunkMapper chunkMapper;
    private final ParentChunkService parentChunkService;

    @Override
    public String getName() {
        return "ParentChildUplift";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        // 1. 收集所有非空 chunkId
        List<String> chunkIds = chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(Objects::nonNull)
                .toList();
        if (chunkIds.isEmpty()) {
            return chunks;
        }

        // 2. 批量查询：哪些 chunk 有父子关系（1 条 SQL）
        List<KnowledgeChunkDO> withParent = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .select(KnowledgeChunkDO::getId, KnowledgeChunkDO::getParentId)
                        .in(KnowledgeChunkDO::getId, chunkIds)
                        .isNotNull(KnowledgeChunkDO::getParentId)
        );

        if (withParent.isEmpty()) {
            return chunks;
        }

        // 3. 构建 chunkId → parentId 映射
        Map<String, String> idToParentId = withParent.stream()
                .collect(Collectors.toMap(KnowledgeChunkDO::getId, KnowledgeChunkDO::getParentId));

        // 4. 批量获取父块内容（1 条 SQL）
        Map<String, String> parentContents = parentChunkService.batchGetParentContent(
                withParent.stream().map(KnowledgeChunkDO::getParentId).toList());

        // 5. 替换有父子关系的子块
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            String parentId = idToParentId.get(chunk.getId());
            if (parentId == null) {
                continue;
            }

            String content = parentContents.get(parentId);
            if (content != null) {
                chunks.set(i, RetrievedChunk.builder()
                        .id(parentId)
                        .text(content)
                        .score(chunk.getScore())
                        .build());
                log.debug("子块上溯：chunkId={}, parentId={}", chunk.getId(), parentId);
            }
        }

        // TODO: 上溯后应该对同一个 parentId 去重

        return chunks;
    }
}
