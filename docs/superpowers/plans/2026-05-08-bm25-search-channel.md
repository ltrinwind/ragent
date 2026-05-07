# BM25 Keyword Search Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a BM25 keyword search channel using PostgreSQL tsvector + zhparser, with RRF fusion to merge vector and keyword results.

**Architecture:** New `KeywordSearchChannel` implements the existing `SearchChannel` interface, executing full-text SQL via `ts_rank_cd`. A new `RrfFusionPostProcessor` merges multi-channel results using Reciprocal Rank Fusion. Both integrate into the existing multi-channel retrieval engine with zero changes to the orchestration flow.

**Tech Stack:** PostgreSQL tsvector + GIN index + zhparser, MyBatis-Plus `@Select` annotation, Spring `@Component`.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `resources/database/schema_pg.sql` | Modify | Add tsvector column + GIN index to `t_knowledge_chunk` |
| `SearchChannelType.java` | Modify | Add `KEYWORD_BM25` enum value |
| `SearchChannelProperties.java` | Modify | Add `KeywordBm25` and `Rrf` config classes |
| `KnowledgeChunkMapper.java` | Modify | Add `searchByKeyword` method |
| `KeywordSearchChannel.java` | Create | BM25 search channel implementation |
| `RrfFusionPostProcessor.java` | Create | RRF fusion post-processor |
| `DeduplicationPostProcessor.java` | Modify | Add `KEYWORD_BM25` to priority switch |
| `application.yaml` | Modify | Add BM25 and RRF configuration entries |

---

### Task 1: Database Schema — Add tsvector Column and GIN Index

**Files:**
- Modify: `resources/database/schema_pg.sql`

- [ ] **Step 1: Add tsvector column and GIN index to schema**

Append the following after the `t_knowledge_chunk` table definition (after the `COMMENT ON COLUMN t_knowledge_chunk.deleted` line), near the existing `ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS parent_id` block:

```sql
-- BM25 全文检索：自动生成 tsvector 列 + GIN 索引（需先安装 zhparser 扩展）
-- CREATE EXTENSION IF NOT EXISTS zhparser;
-- CREATE TEXT SEARCH CONFIGURATION simple_zh (PARSER = zhparser) WITH (MAPPING = ngram);
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple_zh', coalesce(content, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_tsv ON t_knowledge_chunk USING GIN (tsv);
COMMENT ON COLUMN t_knowledge_chunk.tsv IS '全文检索预分词列（由 content 自动生成）';
```

- [ ] **Step 2: Commit**

```bash
git add resources/database/schema_pg.sql
git commit -m "feat(schema): add tsvector column and GIN index for BM25 full-text search"
```

---

### Task 2: SearchChannelType — Add KEYWORD_BM25 Enum Value

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchChannelType.java`

- [ ] **Step 1: Add `KEYWORD_BM25` enum value**

Replace the `KEYWORD_ES` entry with `KEYWORD_BM25`:

```java
    /**
     * BM25 关键词检索
     * 基于 PostgreSQL tsvector 的全文检索
     */
    KEYWORD_BM25,
```

Keep `KEYWORD_ES` if you want both, but the design only uses `KEYWORD_BM25`.

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchChannelType.java
git commit -m "feat(channel): add KEYWORD_BM25 enum value to SearchChannelType"
```

---

### Task 3: SearchChannelProperties — Add BM25 and RRF Config

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SearchChannelProperties.java`

- [ ] **Step 1: Add `KeywordBm25` and `Rrf` inner classes and wire them into `Channels`**

Add two new inner classes and update the `Channels` class:

```java
    @Data
    public static class Channels {

        private VectorGlobal vectorGlobal = new VectorGlobal();

        private IntentDirected intentDirected = new IntentDirected();

        /**
         * BM25 关键词检索配置
         */
        private KeywordBm25 keywordBm25 = new KeywordBm25();

        /**
         * RRF 融合配置
         */
        private Rrf rrf = new Rrf();
    }
```

Add the `KeywordBm25` class:

```java
    @Data
    public static class KeywordBm25 {

        /**
         * 是否启用 BM25 关键词检索通道
         */
        private boolean enabled = true;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 3;

        /**
         * PostgreSQL 全文检索配置名
         * 需与数据库中 tsvector 列使用的配置一致
         */
        private String tsvectorConfig = "simple_zh";
    }
```

Add the `Rrf` class:

```java
    @Data
    public static class Rrf {

        /**
         * 是否启用 RRF 融合
         */
        private boolean enabled = true;

        /**
         * RRF 常数 k
         * 值越大，低排名结果的影响越小
         */
        private int k = 60;
    }
```

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SearchChannelProperties.java
git commit -m "feat(config): add KeywordBm25 and Rrf configuration to SearchChannelProperties"
```

---

### Task 4: KnowledgeChunkMapper — Add Keyword Search Method

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/KnowledgeChunkMapper.java`

- [ ] **Step 1: Add `searchByKeyword` method with `@Select` annotation**

Add imports and method:

```java
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
```

Add the method to the interface:

```java
    /**
     * 基于 tsvector 的全文检索（BM25 近似）
     *
     * @param config PostgreSQL text search config name (e.g. "simple_zh")
     * @param query  搜索关键词
     * @param kbIds  知识库 ID 列表，为空时不限定范围
     * @param topK   返回数量上限
     * @return 包含 id, content, score 的 Map 列表
     */
    @Select("<script>" +
            "SELECT id, content, ts_rank_cd(tsv, query) AS score " +
            "FROM t_knowledge_chunk, plainto_tsquery(#{config}, #{query}) query " +
            "WHERE tsv @@ query " +
            "AND enabled = 1 AND deleted = 0 " +
            "<if test='kbIds != null and kbIds.size() > 0'>" +
            "  AND kb_id IN " +
            "  <foreach collection='kbIds' item='kbId' open='(' separator=',' close=')'>" +
            "    #{kbId}" +
            "  </foreach>" +
            "</if>" +
            "ORDER BY score DESC " +
            "LIMIT #{topK}" +
            "</script>")
    List<Map<String, Object>> searchByKeyword(@Param("config") String config,
                                              @Param("query") String query,
                                              @Param("kbIds") List<String> kbIds,
                                              @Param("topK") int topK);
```

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/KnowledgeChunkMapper.java
git commit -m "feat(mapper): add searchByKeyword method for BM25 full-text search"
```

---

### Task 5: KeywordSearchChannel — Implement BM25 Search Channel

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/KeywordSearchChannel.java`

- [ ] **Step 1: Create `KeywordSearchChannel.java`**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/KeywordSearchChannel.java
git commit -m "feat(channel): implement KeywordSearchChannel for BM25 full-text search"
```

---

### Task 6: RrfFusionPostProcessor — Implement RRF Fusion

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RrfFusionPostProcessor.java`

- [ ] **Step 1: Create `RrfFusionPostProcessor.java`**

```java
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
        // 只有多通道结果时才启用 RRF
        return properties.getChannels().getRrf().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int k = properties.getChannels().getRrf().getK();

        // 单通道或无结果时直接返回
        if (CollUtil.isEmpty(results) || results.size() < 2) {
            log.info("通道数不足 2 个，跳过 RRF 融合");
            return chunks;
        }

        // chunkKey -> 累积 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        // chunkKey -> RetrievedChunk（保留首次出现的实例）
        Map<String, RetrievedChunk> chunkMap = new HashMap<>();

        for (SearchChannelResult channelResult : results) {
            if (CollUtil.isEmpty(channelResult.getChunks())) {
                continue;
            }

            // 按原始 score 降序排名
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

        // 按 RRF 分数降序排列，更新 score 字段
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
```

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RrfFusionPostProcessor.java
git commit -m "feat(postprocessor): implement RrfFusionPostProcessor for multi-channel result fusion"
```

---

### Task 7: Update DeduplicationPostProcessor — Add KEYWORD_BM25 Priority

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java`

- [ ] **Step 1: Add `KEYWORD_BM25` to the `getChannelPriority` switch**

Update the switch statement to include the new enum value:

```java
    private int getChannelPriority(SearchChannelType type) {
        return switch (type) {
            case INTENT_DIRECTED -> 1;
            case KEYWORD_BM25 -> 2;        // BM25 关键词检索
            case KEYWORD_ES -> 3;           // ES 关键词检索（预留）
            case VECTOR_GLOBAL -> 4;
            default -> 99;
        };
    }
```

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java
git commit -m "feat(dedup): add KEYWORD_BM25 to channel priority switch"
```

---

### Task 8: application.yaml — Add BM25 and RRF Configuration

**Files:**
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: Add configuration entries under `rag.search.channels`**

Add after the existing `intent-directed:` block:

```yaml
      keyword-bm25:
        enabled: true
        top-k-multiplier: 3
        tsvector-config: simple_zh
      rrf:
        enabled: true
        k: 60
```

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/resources/application.yaml
git commit -m "feat(config): add BM25 and RRF configuration to application.yaml"
```

---

### Task 9: Build and Verify

- [ ] **Step 1: Run full build with formatting check**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

If Spotless formatting fails, the license header may be missing or misplaced. Verify each new file has the Apache 2.0 header from `resources/format/copyright.txt`.

- [ ] **Step 2: Run tests**

```bash
mvn test
```

Expected: All tests pass

- [ ] **Step 3: Verify Spring context loads**

```bash
mvn -pl bootstrap spring-boot:run
```

Expected: Application starts on port 9090 without errors. Check logs for `KeywordBm25Search` and `RrfFusion` beans being registered.

- [ ] **Step 4: Final commit (if any formatting fixes needed)**

```bash
git add -A
git commit -m "style: apply Spotless formatting to BM25 channel files"
```
