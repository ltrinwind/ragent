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

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {

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
}
