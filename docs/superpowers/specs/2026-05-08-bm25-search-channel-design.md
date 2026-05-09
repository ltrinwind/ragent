# BM25 Keyword Search Channel Design

## Overview

Add a BM25 keyword search channel to the existing multi-channel retrieval architecture, using PostgreSQL tsvector + zhparser for Chinese full-text search, with RRF (Reciprocal Rank Fusion) to merge results from vector and keyword channels.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tokenization | PostgreSQL tsvector + zhparser | Native DB support, no app-layer dependency |
| Activation strategy | Always active | Simplest implementation, no intent logic needed |
| BM25 approximation | `ts_rank_cd()` | PostgreSQL built-in TF/IDF scoring |
| Fusion method | RRF in postprocessor chain | Order-independent, configurable, fits existing architecture |
| Implementation approach | Pure SQL | Minimal code changes, leverages DB indexing |

## Database Layer

### Schema Change

Add to `t_knowledge_chunk`:

```sql
-- Auto-generated tsvector column from content
ALTER TABLE t_knowledge_chunk ADD COLUMN tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple_zh', coalesce(content, ''))) STORED;

-- GIN index for fast full-text search
CREATE INDEX idx_knowledge_chunk_tsv ON t_knowledge_chunk USING GIN (tsv);
```

### Query SQL

kbIds is optional — when intent analysis identifies specific knowledge bases, filter by them; otherwise search globally across all knowledge bases.

```sql
SELECT id, kb_id, doc_id, content,
       ts_rank_cd(tsv, query) AS score
FROM t_knowledge_chunk,
     plainto_tsquery('simple_zh', #{query}) query
WHERE tsv @@ query
  AND enabled = 1
  AND deleted = 0
  <if test="kbIds != null and kbIds.size() > 0">
    AND kb_id IN
    <foreach collection="kbIds" item="id" open="(" separator="," close=")">
      #{id}
    </foreach>
  </if>
ORDER BY score DESC
LIMIT #{topK}
```

## New Components

### 1. KeywordSearchChannel

- **Package**: `com.nageoffer.ai.ragent.rag.core.retrieve.channel`
- **Type**: `SearchChannelType.KEYWORD_BM25` (new enum value)
- **Priority**: 5 (between IntentDirected=1 and VectorGlobal=10)
- **isEnabled()**: always returns `true` (configurable via `rag.search.channels.keyword-bm25.enabled`)
- **search()**: delegates to `KeywordSearchMapper` for tsvector query; tries to extract kbIds from SearchContext intent results, falls back to null (global search) if no intents identified
- **TopK**: `defaultTopK * top-k-multiplier` (same pattern as existing channels)

### 2. KeywordSearchMapper

- **Package**: `com.nageoffer.ai.ragent.knowledge.dao.mapper`
- **Method**: `List<RetrievedChunk> searchByKeyword(@Param("query") String query, @Param("kbIds") List<String> kbIds, @Param("topK") int topK)`
- **XML**: contains tsvector query SQL with `ts_rank_cd` scoring

### 3. RrfFusionPostProcessor

- **Package**: `com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor`
- **Order**: 5 (after Deduplication=1, before Rerank=10)
- **Algorithm**: `RRF_score(d) = sum(1 / (k + rank_i))` where k=60 (configurable)
- **Input**: list of `SearchChannelResult` from multiple channels
- **Output**: fused and re-ranked chunk list

### 4. SearchChannelType.KEYWORD_BM25

New enum value added to `SearchChannelType`.

## Configuration

Added to `SearchChannelProperties`:

```yaml
rag:
  search:
    channels:
      keyword-bm25:
        enabled: true
        top-k-multiplier: 3
        tsvector-config: simple_zh
      rrf:
        k: 60
```

## Postprocessor Chain Order

```
1  DeduplicationPostProcessor  — remove duplicate chunks across channels
5  RrfFusionPostProcessor      — RRF merge multi-channel results
10 RerankPostProcessor         — LLM-based reranking for final ranking
```

## Files Changed

| File | Action | Description |
|------|--------|-------------|
| `resources/database/schema_pg.sql` | Modify | Add tsv column + GIN index |
| `SearchChannelType.java` | Modify | Add `KEYWORD_BM25` enum value |
| `SearchChannelProperties.java` | Modify | Add keyword-bm25 and rrf config fields |
| `KeywordSearchChannel.java` | Create | BM25 search channel implementation |
| `KeywordSearchMapper.java` | Create | MyBatis mapper for tsvector query |
| `KeywordSearchMapper.xml` | Create | SQL mapping for full-text search |
| `RrfFusionPostProcessor.java` | Create | RRF fusion postprocessor |
| `MultiChannelRetrievalEngine.java` | Modify | Ensure RRF postprocessor integrates correctly |
| `application.yaml` | Modify | Add BM25 and RRF configuration |
