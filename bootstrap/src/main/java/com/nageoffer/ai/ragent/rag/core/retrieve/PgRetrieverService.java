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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgRetrieverService implements RetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final KnowledgeChunkMapper chunkMapper;
    private final SearchChannelProperties properties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        List<Float> embedding = embeddingService.embed(request.getQuery());
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        // 设置ef_search提升召回率
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET hnsw.ef_search = 200");

        String vectorLiteral = toVectorLiteral(vector);
        double scoreThreshold = properties.getChannels().getVectorGlobal().getScoreThreshold();
        // JOIN t_knowledge_chunk 以获取 content_type, image_url, image_mime_type
        // 相似度下限过滤：低于 scoreThreshold 的 chunk 在召回源头即丢弃，不进入 RRF / Rerank
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query(
                "SELECT v.id, v.content, c.content_type, c.image_url, c.image_mime_type, " +
                        "1 - (v.embedding <=> ?::vector) AS score " +
                        "FROM t_knowledge_vector v " +
                        "JOIN t_knowledge_chunk c ON c.id = v.id " +
                        "WHERE v.metadata->>'collection_name' = ? " +
                        "AND c.enabled = 1 AND c.deleted = 0 " +
                        "AND 1 - (v.embedding <=> ?::vector) >= ? " +
                        "ORDER BY v.embedding <=> ?::vector LIMIT ?",
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .contentType(rs.getString("content_type"))
                        .imageUrl(rs.getString("image_url"))
                        .imageMimeType(rs.getString("image_mime_type"))
                        .score(rs.getFloat("score"))
                        .build(),
                vectorLiteral, request.getCollectionName(), vectorLiteral, scoreThreshold, vectorLiteral, request.getTopK()
        );
    }

    @Override
    public List<RetrievedChunk> retrieveByKeyword(String query, List<String> kbIds, int topK) {
        String config = properties.getChannels().getKeywordBm25().getTsvectorConfig();
        List<Map<String, Object>> rows = chunkMapper.searchByKeyword(config, query, kbIds, topK);
        return rows.stream()
                .map(row -> RetrievedChunk.builder()
                        .id(String.valueOf(row.get("id")))
                        .text(String.valueOf(row.get("content")))
                        .contentType(row.get("content_type") != null ? String.valueOf(row.get("content_type")) : null)
                        .imageUrl(row.get("image_url") != null ? String.valueOf(row.get("image_url")) : null)
                        .imageMimeType(row.get("image_mime_type") != null ? String.valueOf(row.get("image_mime_type")) : null)
                        .score(((Number) row.get("score")).floatValue())
                        .build())
                .toList();
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
