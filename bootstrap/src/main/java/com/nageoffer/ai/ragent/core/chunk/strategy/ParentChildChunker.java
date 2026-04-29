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

package com.nageoffer.ai.ragent.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.core.chunk.ParentChildOptions;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子分块器
 * 先按 parentChunkSize 切出父块，再在每个父块内部按 childChunkSize 切出子块。
 * 子块通过 metadata._parentId 关联父块，父块通过 metadata._role="parent" 标识。
 * 只有子块会被嵌入向量，父块仅存储用于检索时上溯上下文。
 */
@Component
public class ParentChildChunker implements ChunkingStrategy {

    private static final List<String> SEPARATORS = List.of(
            "\n\n",
            "\n",
            "。", "！", "？",
            ".", "!", "?",
            ";", "；",
            " ",
            ""
    );

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.PARENT_CHILD;
    }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        ParentChildOptions opts = (ParentChildOptions) config;
        int parentSize = Math.max(1, opts.parentChunkSize());
        int childSize = Math.max(1, opts.childChunkSize());
        int overlap = Math.min(Math.max(0, opts.overlapSize()), childSize - 1);

        if (childSize >= parentSize) {
            childSize = parentSize;
        }

        // Phase 1: 按父块大小递归切分
        List<String> parentSegments = recursiveSplit(normalized, SEPARATORS, 0, parentSize);

        // Phase 2: 每个父块内部切子块，构建带层级关系的 VectorChunk 列表
        List<VectorChunk> result = new ArrayList<>();
        int globalIndex = 0;

        for (String parentContent : parentSegments) {
            String parentId = IdUtil.getSnowflakeNextIdStr();

            // 父块
            result.add(VectorChunk.builder()
                    .chunkId(parentId)
                    .index(globalIndex++)
                    .content(parentContent)
                    .parent(true)
                    .build());

            // 子块
            List<String> childSegments = recursiveSplit(parentContent, SEPARATORS, 0, childSize);
            List<VectorChunk> children = mergeWithOverlap(childSegments, childSize, overlap, parentId, globalIndex);
            globalIndex += children.size();
            result.addAll(children);
        }

        return result;
    }

    private List<String> recursiveSplit(String text, List<String> separators, int sepIndex, int chunkSize) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }
        if (sepIndex >= separators.size()) {
            return hardSplit(text, chunkSize);
        }
        String separator = separators.get(sepIndex);
        if (separator.isEmpty()) {
            return hardSplit(text, chunkSize);
        }
        List<String> parts = splitBySeparator(text, separator);
        if (parts.size() <= 1) {
            return recursiveSplit(text, separators, sepIndex + 1, chunkSize);
        }
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.length() <= chunkSize) {
                result.add(part);
            } else {
                result.addAll(recursiveSplit(part, separators, sepIndex + 1, chunkSize));
            }
        }
        return result;
    }

    private List<String> splitBySeparator(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int idx = 0;
        while (idx < text.length()) {
            int next = text.indexOf(separator, idx);
            if (next < 0) {
                parts.add(text.substring(idx));
                break;
            }
            int end = next + separator.length();
            parts.add(text.substring(idx, end));
            idx = end;
        }
        return parts;
    }

    private List<String> hardSplit(String text, int chunkSize) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                end = findBoundary(text, start, end);
            }
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }

    private int findBoundary(String text, int start, int targetEnd) {
        int lookback = Math.min(targetEnd - start, targetEnd / 4);
        for (int i = 0; i <= lookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            char c = text.charAt(pos);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；') {
                return pos + 1;
            }
            if (c == '.' || c == '!' || c == '?' || c == ';') {
                int next = pos + 1;
                if (next >= text.length() || Character.isWhitespace(text.charAt(next))) {
                    return next;
                }
            }
        }
        return targetEnd;
    }

    private List<VectorChunk> mergeWithOverlap(List<String> splits, int childSize, int overlap,
                                                String parentId, int startIndex) {
        if (splits.isEmpty()) {
            return List.of();
        }
        List<VectorChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = startIndex;

        for (String split : splits) {
            if (current.length() + split.length() > childSize && current.length() > 0) {
                chunks.add(buildChildChunk(current.toString(), parentId, index++));
                if (overlap > 0) {
                    String tail = tailByChars(current.toString(), overlap);
                    current.setLength(0);
                    current.append(tail);
                } else {
                    current.setLength(0);
                }
            }
            current.append(split);
        }

        if (current.length() > 0 && StringUtils.hasText(current.toString().strip())) {
            chunks.add(buildChildChunk(current.toString(), parentId, index));
        }

        return chunks;
    }

    private VectorChunk buildChildChunk(String content, String parentId, int index) {
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(index)
                .content(content)
                .parentId(parentId)
                .build();
    }

    private String tailByChars(String s, int n) {
        if (n <= 0) return "";
        int len = s.length();
        return len <= n ? s : s.substring(len - n);
    }
}
