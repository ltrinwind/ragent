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
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.core.chunk.TextBoundaryOptions;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知分块器（Markdown 友好版）
 * 混合递归分块策略
 * Phase 1: 按 Markdown 结构（标题、代码块、图片、段落）识别 block 边界
 * Phase 2: 对超大 block 内部使用递归分块策略按多级分隔符二次切分
 * Phase 3: 贪心合并相邻小块到 targetChars，并在 chunk 间添加 overlap
 */
@Component
public class StructureAwareTextChunker implements ChunkingStrategy {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern ATOMIC_IMAGE = Pattern.compile("^!\\[[^]]*]\\([^)]+\\)(?:\\s*\"[^\"]*\")?\\s*$");
    private static final Pattern ATOMIC_LINK = Pattern.compile("^\\[[^]]+]\\([^)]+\\)\\s*$");

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
        return ChunkingMode.STRUCTURE_AWARE;
    }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (StrUtil.isBlank(text)) return List.of();

        text = text.replace("\r\n", "\n").replace("\r", "\n");

        TextBoundaryOptions opts = (TextBoundaryOptions) config;
        int target = opts.targetChars();
        int max = opts.maxChars();
        int min = opts.minChars();
        int overlap = opts.overlapChars();

        // Phase 1: 结构化识别 block
        List<Block> blocks = segmentToBlocks(text);
        if (blocks.isEmpty()) {
            return List.of(VectorChunk.builder()
                    .content(text).index(0).chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build());
        }

        // Phase 2: 遍历 block，提取原串，超限则递归二次切分
        List<String> segments = splitBlocksRecursive(blocks, text, target, max);

        // Phase 3: 贪心合并 + overlap，构建最终 VectorChunk
        return mergeAndBuildChunks(segments, target, min, max, overlap);
    }

    // ==================== Phase 1: 结构化识别（不变） ====================

    @Getter
    @ToString
    @AllArgsConstructor
    private static class Block {
        enum Kind {HEADING, CODE, ATOMIC, PARA}

        final Block.Kind kind;
        final int start;
        final int end;
    }

    /**
     * Phase 1: 线性扫描原文，按 Markdown 语法边界将文本拆分为结构化 block
     * 识别标题（#{1,6}）、代码围栏（```...```）、原子行（图片/链接）、普通段落四种类型，
     * 每个 block 记录在原文中的 [start, end) 下标，不修改原文内容
     */
    private List<Block> segmentToBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int n = text.length();
        int pos = 0;

        boolean inFence = false;
        int fenceStart = -1;

        boolean inPara = false;
        int paraStart = -1;

        while (pos < n) {
            int lineEnd = indexOfNl(text, pos);
            int lineEndNl = lineEnd < n && text.charAt(lineEnd) == '\n' ? lineEnd + 1 : lineEnd;
            String line = text.substring(pos, lineEnd);
            String trimmed = trimRightKeepLeft(line);

            if (!inFence && CODE_FENCE.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                inFence = true;
                fenceStart = pos;
                pos = lineEndNl;
                continue;
            }

            if (inFence) {
                if (CODE_FENCE.matcher(trimmed).matches()) {
                    blocks.add(new Block(Block.Kind.CODE, fenceStart, lineEndNl));
                    inFence = false;
                }
                pos = lineEndNl;
                continue;
            }

            if (trimmed.isEmpty()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                pos = lineEndNl;
                continue;
            }

            if (HEADING.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.HEADING, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }
            if (ATOMIC_IMAGE.matcher(trimmed).matches() || ATOMIC_LINK.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.ATOMIC, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }

            if (!inPara) {
                inPara = true;
                paraStart = pos;
            }
            pos = lineEndNl;
        }

        if (inFence) {
            blocks.add(new Block(Block.Kind.CODE, fenceStart, n));
        } else if (inPara) {
            blocks.add(new Block(Block.Kind.PARA, paraStart, n));
        }
        return coalesceTrailingBlanks(blocks, text);
    }

    /**
     * 合并相邻 block 之间的纯空白区域到前一个 block 内部，
     * 避免单独产生空白 block，同时保持原文内容不变
     */
    private List<Block> coalesceTrailingBlanks(List<Block> blocks, String text) {
        if (blocks.isEmpty()) return blocks;
        List<Block> out = new ArrayList<>();
        Block prev = blocks.get(0);
        for (int i = 1; i < blocks.size(); i++) {
            Block cur = blocks.get(i);
            if (isAllBlank(text, prev.end, cur.start)) {
                prev = new Block(prev.kind, prev.start, cur.start);
            }
            out.add(prev);
            prev = cur;
        }
        out.add(prev);
        return out;
    }

    // ==================== Phase 2: block → 递归分块 ====================

    /**
     * 遍历每个 block，提取原文子串：
     * - 不超 target 的 block 直接作为一个 segment
     * - 超过 target 的 block 调用递归分块拆成多个 segment
     * - HEADING/CODE/ATOMIC 类型保持完整，不拆分（即使超限也作为整体）
     */
    private List<String> splitBlocksRecursive(List<Block> blocks, String text, int target, int max) {
        List<String> segments = new ArrayList<>();
        for (Block block : blocks) {
            String content = text.substring(block.start, block.end);
            // 结构性 block（标题、代码块、原子元素）保持完整
            if (block.kind != Block.Kind.PARA || content.length() <= target) {
                segments.add(content);
                continue;
            }
            // PARA 超限：递归二次切分（超过 max 时用 max 作为目标大小兜底）
            int effectiveTarget = Math.min(content.length(), max);
            effectiveTarget = Math.min(effectiveTarget, target);
            segments.addAll(recursiveSplit(content, SEPARATORS, 0, effectiveTarget));
        }
        return segments;
    }

    /**
     * 递归切分：用 separators[sepIndex] 切文本，超限段降级到下一级分隔符
     */
    private List<String> recursiveSplit(String text, List<String> separators, int sepIndex, int target) {
        if (text.length() <= target) {
            return List.of(text);
        }
        if (sepIndex >= separators.size()) {
            return hardSplit(text, target);
        }

        String separator = separators.get(sepIndex);
        if (separator.isEmpty()) {
            return hardSplit(text, target);
        }

        List<String> parts = splitBySeparator(text, separator);
        if (parts.size() <= 1) {
            return recursiveSplit(text, separators, sepIndex + 1, target);
        }

        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.length() <= target) {
                result.add(part);
            } else {
                result.addAll(recursiveSplit(part, separators, sepIndex + 1, target));
            }
        }
        return result;
    }

    /**
     * 按指定分隔符切分文本，分隔符保留在前面片段的尾部（而非丢弃）
     * 例如 "a\nb\nc" 用 "\n" 切分得到 ["a\n", "b\n", "c"]
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
     * 兜底硬切分：当所有分隔符都无法拆分时，按 chunkSize 强制截断，
     * 在截断点附近尝试对齐到换行或句末标点，找不到则直接在 chunkSize 处切断
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
     * 在 [targetEnd - lookBack, targetEnd] 范围内向左搜索换行符或句末标点作为截断边界，
     * lookBack 取 targetEnd / 4，在"尽量对齐"和"不会回退太远"之间取平衡
     */
    private int findBoundary(String text, int start, int targetEnd) {
        int lookBack = Math.min(targetEnd - start, targetEnd / 4);
        for (int i = 0; i <= lookBack; i++) {
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

    // ==================== Phase 3: 贪心合并 + overlap ====================

    /**
     * 将 segments 贪心合并为最终 chunk：
     * - 相邻 segment 总量 <= target 时合并
     * - 超过 max 时强制切割
     * - 合并后不足 min 的尝试与下一轮合并
     * - chunk 间通过复制前一个 chunk 尾部 overlap 字符实现重叠
     */
    private List<VectorChunk> mergeAndBuildChunks(List<String> segments, int target, int min, int max, int overlap) {
        if (segments.isEmpty()) return List.of();

        List<VectorChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String prevTail = null;
        int index = 0;

        for (String seg : segments) {
            int currentLen = current.length();
            int mergedLen = currentLen + seg.length();

            // 当前块非空，且加入这个 segment 后超过 target → 先提交当前块
            if (currentLen > 0 && mergedLen > target) {
                // 太小的不提交，继续攒
                if (currentLen >= min) {
                    chunks.add(buildChunk(current.toString(), prevTail, overlap, index++));
                    prevTail = tailByChars(current.toString(), overlap);
                    current.setLength(0);
                }
            }

            // 加入这个 segment 后超过 max → 强制提交（即使不足 min）
            if (!current.isEmpty() && current.length() + seg.length() > max) {
                chunks.add(buildChunk(current.toString(), prevTail, overlap, index++));
                prevTail = tailByChars(current.toString(), overlap);
                current.setLength(0);
            }

            current.append(seg);
        }

        // 处理剩余内容
        if (!current.isEmpty() && StrUtil.isNotBlank(current.toString().strip())) {
            // 最后一个 chunk 太小时尝试与前一个合并
            if (current.length() < min && !chunks.isEmpty()) {
                VectorChunk last = chunks.remove(chunks.size() - 1);
                String merged = last.getContent() + current;
                chunks.add(VectorChunk.builder()
                        .chunkId(last.getChunkId())
                        .index(last.getIndex())
                        .content(merged)
                        .build());
            } else {
                chunks.add(buildChunk(current.toString(), prevTail, overlap, index));
            }
        }

        return chunks;
    }

    /**
     * 构建单个 VectorChunk：将前一个 chunk 的尾部 overlap 字符拼接到当前内容开头，
     * 实现相邻 chunk 之间的上下文重叠
     */
    private VectorChunk buildChunk(String content, String prevTail, int overlap, int index) {
        String finalContent = (overlap > 0 && prevTail != null && !prevTail.isEmpty())
                ? prevTail + content
                : content;
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(index)
                .content(finalContent)
                .build();
    }

    // ==================== 工具方法 ====================

    /**
     * 从 from 位置开始查找下一个换行符下标，找不到则返回字符串长度
     */
    private int indexOfNl(String s, int from) {
        int p = s.indexOf('\n', from);
        return p < 0 ? s.length() : p;
    }

    /**
     * 去除行尾空白（空格/制表），但保留左侧缩进和换行符，用于标题/代码围栏的正则匹配
     */
    private String trimRightKeepLeft(String s) {
        int r = s.length();
        while (r > 0 && Character.isWhitespace(s.charAt(r - 1)) && s.charAt(r - 1) != '\n' && s.charAt(r - 1) != '\r') {
            r--;
        }
        return s.substring(0, r);
    }

    /**
     * 判断原文 [from, to) 区间是否全部为空白字符（空格/制表/回车/换行）
     */
    private boolean isAllBlank(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!(c == ' ' || c == '\t' || c == '\r' || c == '\n')) return false;
        }
        return true;
    }

    /**
     * 截取字符串末尾 n 个字符，用于实现相邻 chunk 之间的 overlap 重叠
     */
    private String tailByChars(String s, int n) {
        if (n <= 0) return "";
        int len = s.length();
        return len <= n ? s : s.substring(len - n);
    }
}
