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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF (Reciprocal Rank Fusion) 融合后置处理器
 * <p>
 * 读取原始多通道检索结果（results），对每个通道内部按 score 排名，
 * 然后使用 RRF 公式融合：RRF_score(d) = Σ 1/(k + rank_i)
 * <p>
 * 执行顺序：order=5（在去重之后、Rerank 之前）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RrfFusionPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "RrfFusion";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getRrf().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int k = properties.getChannels().getRrf().getK();

        if (CollUtil.isEmpty(results) || results.size() < 2) {
            log.info("通道数不足 2 个，跳过 RRF 融合");
            return chunks;
        }

        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievedChunk> chunkMap = new HashMap<>();

        for (SearchChannelResult channelResult : results) {
            if (CollUtil.isEmpty(channelResult.getChunks())) {
                continue;
            }

            List<RetrievedChunk> sorted = channelResult.getChunks().stream()
                    .sorted(Comparator.comparingDouble(RetrievedChunk::getScore).reversed())
                    .toList();

            for (int rank = 0; rank < sorted.size(); rank++) {
                RetrievedChunk chunk = sorted.get(rank);
                String key = chunk.getId() != null
                        ? chunk.getId()
                        : String.valueOf(chunk.getText().hashCode());

                double contribution = 1.0 / (k + rank + 1);
                rrfScores.merge(key, contribution, Double::sum);

                if (!chunkMap.containsKey(key)) {
                    chunkMap.put(key, chunk);
                }
            }
        }

        List<RetrievedChunk> fused = new ArrayList<>();
        rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> {
                    RetrievedChunk chunk = chunkMap.get(entry.getKey());
                    chunk.setScore(entry.getValue().floatValue());
                    fused.add(chunk);
                });

        log.info("RRF 融合完成，输入通道数：{}，融合后 Chunk 数：{}",
                results.size(), fused.size());

        return fused;
    }
}
