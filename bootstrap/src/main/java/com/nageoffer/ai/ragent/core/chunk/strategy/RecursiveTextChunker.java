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
import com.nageoffer.ai.ragent.core.chunk.RecursiveOptions;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归分块器
 * 按多级分隔符优先级递归切分文本：先用高级别分隔符（段落边界），切不开再用低级别（句子、空格），
 * 直到每块不超过 chunkSize。相邻块保留 overlapSize 重叠。
 */
@Component
public class RecursiveTextChunker implements ChunkingStrategy {

    private static final List<String> DEFAULT_SEPARATORS = List.of(
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
        return ChunkingMode.RECURSIVE;
    }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");

        RecursiveOptions opts = (RecursiveOptions) config;
        int chunkSize = Math.max(1, opts.chunkSize());
        int overlap = Math.min(Math.max(0, opts.overlapSize()), chunkSize - 1);
        List<String> separators = opts.separators() != null && !opts.separators().isEmpty()
                ? opts.separators()
                : DEFAULT_SEPARATORS;

        List<String> splits = recursiveSplit(normalized, separators, 0, chunkSize);
        return mergeWithOverlap(splits, chunkSize, overlap);
    }

    /**
     * 递归切分：用 separators[sepIndex] 切文本，对超出 chunkSize 的段用下一级分隔符继续切
     */
    private List<String> recursiveSplit(String text, List<String> separators, int sepIndex, int chunkSize) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        // 所有分隔符都耗尽，强制按字符数截断
        if (sepIndex >= separators.size()) {
            return hardSplit(text, chunkSize);
        }

        String separator = separators.get(sepIndex);

        // 空字符串分隔符表示回退到硬切分
        if (separator.isEmpty()) {
            return hardSplit(text, chunkSize);
        }

        List<String> parts = splitBySeparator(text, separator);

        // 当前分隔符没产生任何切分，降级到下一级
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

    /**
     * 按指定分隔符切分文本，保留分隔符在前面片段的尾部
     */
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

    /**
     * 硬切分：在 chunkSize 处尝试对齐到换行/句末，找不到则直接截断
     */
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

    /**
     * 在 [targetEnd - lookback, targetEnd] 范围内找换行/句末标点作为边界
     */
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

    /**
     * 将切好的片段合并为最终 chunk：相邻小片段可合并到 chunkSize 以内，同时处理 overlap
     */
    private List<VectorChunk> mergeWithOverlap(List<String> splits, int chunkSize, int overlap) {
        if (splits.isEmpty()) {
            return List.of();
        }

        List<VectorChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String split : splits) {
            if (current.length() + split.length() > chunkSize && current.length() > 0) {
                chunks.add(buildChunk(current.toString(), index++));
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
            chunks.add(buildChunk(current.toString(), index));
        }

        return chunks;
    }

    private VectorChunk buildChunk(String content, int index) {
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(index)
                .content(content)
                .build();
    }

    private String tailByChars(String s, int n) {
        if (n <= 0) return "";
        int len = s.length();
        return len <= n ? s : s.substring(len - n);
    }
}
