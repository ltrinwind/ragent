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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * BM25 关键词检索通道
 * <p>
 * 基于 PostgreSQL tsvector + zhparser 的全文检索，
 * 使用 ts_rank_cd 评分（TF/IDF 近似 BM25）。
 * 始终激活，与向量通道并行执行，结果由 RRF 融合。
 */
@Slf4j
@Component
public class KeywordSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final KnowledgeChunkMapper chunkMapper;

    public KeywordSearchChannel(SearchChannelProperties properties,
                                KnowledgeChunkMapper chunkMapper) {
        this.properties = properties;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public String getName() {
        return "KeywordBm25Search";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getKeywordBm25().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            String question = context.getMainQuestion();
            List<String> kbIds = resolveKbIds(context);
            int topK = context.getTopK() * properties.getChannels().getKeywordBm25().getTopKMultiplier();
            String config = properties.getChannels().getKeywordBm25().getTsvectorConfig();

            log.info("执行 BM25 关键词检索，问题：{}，限定知识库：{}，TopK：{}",
                    question,
                    CollUtil.isEmpty(kbIds) ? "全部" : kbIds,
                    topK);

            List<Map<String, Object>> rows = chunkMapper.searchByKeyword(config, question, kbIds, topK);

            List<RetrievedChunk> chunks = rows.stream()
                    .map(row -> RetrievedChunk.builder()
                            .id(String.valueOf(row.get("id")))
                            .text(String.valueOf(row.get("content")))
                            .score(((Number) row.get("score")).floatValue())
                            .build())
                    .toList();

            long latency = System.currentTimeMillis() - startTime;
            log.info("BM25 关键词检索完成，检索到 {} 个 Chunk，耗时 {}ms", chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_BM25)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("BM25 关键词检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_BM25)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD_BM25;
    }

    /**
     * 从意图结果中提取 kbId 列表。
     * 有意图时限定知识库范围，无意图时返回 null 触发全局搜索。
     */
    private List<String> resolveKbIds(SearchContext context) {
        if (CollUtil.isEmpty(context.getIntents())) {
            return null;
        }

        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();

        List<NodeScore> kbIntents = NodeScoreFilters.kb(allScores);
        if (CollUtil.isEmpty(kbIntents)) {
            return null;
        }

        List<String> kbIds = kbIntents.stream()
                .map(ns -> ns.getNode().getKbId())
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        return kbIds.isEmpty() ? null : kbIds;
    }
}
