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

package com.nageoffer.ai.ragent.knowledge.service;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkParentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkParentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 父块服务
 * 负责父块的查询，供检索层在子块命中时上溯获取父块上下文。
 */
@Service
@RequiredArgsConstructor
public class ParentChunkService {

    private final KnowledgeChunkParentMapper parentChunkMapper;

    /**
     * 根据父块 ID 获取父块全文内容
     *
     * @param parentId 父块 ID
     * @return 父块全文内容，不存在时返回 null
     */
    public String getParentContent(String parentId) {
        if (parentId == null) {
            return null;
        }
        KnowledgeChunkParentDO parent = parentChunkMapper.selectById(parentId);
        return parent != null ? parent.getContent() : null;
    }

    /**
     * 根据父块 ID 获取父块完整实体
     *
     * @param parentId 父块 ID
     * @return 父块实体，不存在时返回 null
     */
    public KnowledgeChunkParentDO getById(String parentId) {
        if (parentId == null) {
            return null;
        }
        return parentChunkMapper.selectById(parentId);
    }

    /**
     * 批量获取父块全文内容
     *
     * @param parentIds 父块 ID 列表
     * @return parentId → content 映射
     */
    public Map<String, String> batchGetParentContent(List<String> parentIds) {
        if (CollUtil.isEmpty(parentIds)) {
            return Collections.emptyMap();
        }
        List<String> uniqueIds = parentIds.stream().distinct().toList();
        List<KnowledgeChunkParentDO> parents = parentChunkMapper.selectBatchIds(uniqueIds);
        return parents.stream()
                .collect(Collectors.toMap(KnowledgeChunkParentDO::getId, KnowledgeChunkParentDO::getContent));
    }
}
