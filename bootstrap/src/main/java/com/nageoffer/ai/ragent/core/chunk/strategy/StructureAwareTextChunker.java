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
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 结构感知分块器（Header-Stack 版）
 * 参照 LangChain ExperimentalMarkdownSyntaxTextSplitter 的逐行扫描 + header-stack 思路，
 * 通过维护标题层级栈确保 chunk 边界尊重 Markdown 标题层级关系。
 *
 * Phase 1: 逐行扫描 + header-stack — 将 Markdown 按结构（标题、代码块、水平线、图片、段落）
 *          拆分为 Section，每个 Section 携带完整标题路径
 * Phase 2: 递归切分 — 对超大 PARA Section 内部按多级分隔符二次切分
 * Phase 3: 结构感知合并 — 合并相邻小 Section，尊重标题层级边界
 * Phase 4: 构建 VectorChunk — 添加 overlap 和标题路径 metadata
 */
@Component
public class StructureAwareTextChunker implements ChunkingStrategy {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^ {0,3}([-*_])\\s*\\1\\s*\\1(?:\\s*\\1)*\\s*$");
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

        // Phase 1: 逐行扫描 + header-stack → Section 列表
        List<Section> sections = scanToSections(text);
        if (sections.isEmpty()) {
            return List.of(VectorChunk.builder()
                    .content(text).index(0).chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build());
        }

        // Phase 2: 对超大 PARA Section 递归切分
        List<Section> split = splitOversizedSections(sections, target, max);

        // Phase 3: 结构感知合并
        List<MergedSection> merged = mergeSections(split, target, min, max);

        // Phase 4: 构建 VectorChunk（overlap + metadata）
        return buildChunks(merged, overlap);
    }

    // ==================== 数据结构 ====================

    @Getter
    @AllArgsConstructor
    private static class HeaderEntry {
        final int level;
        final String title;
    }

    @Getter
    @AllArgsConstructor
    private static class Section {
        // HR: --- 的水平线
        enum Kind {HEADING, CODE, HR, ATOMIC, PARA}

        final Section.Kind kind;
        final String content;
        final List<HeaderEntry> headerPath;
        final int splitGroupId;  // 0 = not from recursive split
    }

    @Getter
    @AllArgsConstructor
    private static class MergedSection {
        final String content;
        final List<HeaderEntry> headerPath;
        final int splitGroupId;  // > 0 only if all sections in merge share the same group
    }

    // ==================== Phase 1: 逐行扫描 + header-stack ====================

    /**
     * 将 Markdown 文本逐行扫描，按结构边界拆分为 Section 列表。
     * 使用 Deque<headerentry> 维护标题层级栈，每个 Section 携带当前标题路径快照。
     * 算法与 LangChain ExperimentalMarkdownSyntaxTextSplitter.split_text 一致。
     */
    private List<Section> scanToSections(String text) {
        List<Section> sections = new ArrayList<>();
        Deque<HeaderEntry> headerStack = new ArrayDeque<>();

        String[] lines = text.split("\n", -1);
        int lineIdx = 0;

        boolean inCodeBlock = false;
        StringBuilder codeContent = new StringBuilder();
        StringBuilder currentContent = new StringBuilder();
        List<HeaderEntry> currentHeaders = new ArrayList<>();

        while (lineIdx < lines.length) {
            String rawLine = lines[lineIdx];
            String stripped = rawLine.strip();

            // ---- 代码块内部：收集直到闭合围栏 ----
            if (inCodeBlock) {
                codeContent.append(rawLine).append("\n");
                // 结束代码块
                if (CODE_FENCE.matcher(stripped).matches()) {
                    sections.add(new Section(Section.Kind.CODE, codeContent.toString(),
                            new ArrayList<>(headerStack), 0));
                    inCodeBlock = false;
                    codeContent.setLength(0);
                }
                lineIdx++;
                continue;
            }

            // ---- 检测代码围栏开始 ----
            if (CODE_FENCE.matcher(stripped).matches()) {
                finalizeCurrentSection(sections, currentContent, currentHeaders);
                currentContent.setLength(0);
                currentHeaders = null;
                inCodeBlock = true;
                codeContent.append(rawLine).append("\n");
                lineIdx++;
                continue;
            }

            // ---- 空行：结束已有段落内容 ----
            if (stripped.isEmpty()) {
                if (!currentContent.isEmpty()) {
                    finalizeCurrentSection(sections, currentContent, currentHeaders);
                    currentContent.setLength(0);
                    currentHeaders = null;
                }
                lineIdx++;
                continue;
            }

            // ---- 水平线 ---/***/___ ----,结束已有段落内容
            if (HORIZONTAL_RULE.matcher(stripped).matches()) {
                finalizeCurrentSection(sections, currentContent, currentHeaders);
                currentContent.setLength(0);
                currentHeaders = null;
                sections.add(new Section(Section.Kind.HR, rawLine + "\n",
                        new ArrayList<>(headerStack), 0));
                lineIdx++;
                continue;
            }

            // ---- 标题 ----,结束已有段落内容
            if (HEADING.matcher(stripped).matches()) {
                finalizeCurrentSection(sections, currentContent, currentHeaders);
                currentContent.setLength(0);
                currentHeaders = null;

                // 更新 header stack：弹出 >= 当前层级，压入新标题
                int level = headingLevel(stripped);
                String title = stripped.substring(level).strip();
                while (!headerStack.isEmpty() && headerStack.peekLast().level >= level) {
                    headerStack.removeLast();
                }
                headerStack.addLast(new HeaderEntry(level, title));

                sections.add(new Section(Section.Kind.HEADING, rawLine + "\n",
                        new ArrayList<>(headerStack), 0));
                lineIdx++;
                continue;
            }

            // ---- 原子元素（图片/链接） ----
            if (ATOMIC_IMAGE.matcher(stripped).matches() || ATOMIC_LINK.matcher(stripped).matches()) {
                finalizeCurrentSection(sections, currentContent, currentHeaders);
                currentContent.setLength(0);
                currentHeaders = null;
                sections.add(new Section(Section.Kind.ATOMIC, rawLine + "\n",
                        new ArrayList<>(headerStack), 0));
                lineIdx++;
                continue;
            }

            // ---- 普通段落：累积到 currentContent ----
            if (currentHeaders == null) {
                currentHeaders = new ArrayList<>(headerStack);
            }
            currentContent.append(rawLine).append("\n");
            lineIdx++;
        }

        // 收尾：未闭合的代码块
        if (inCodeBlock) {
            sections.add(new Section(Section.Kind.CODE, codeContent.toString(),
                    new ArrayList<>(headerStack), 0));
        }

        // 收尾：剩余段落内容
        finalizeCurrentSection(sections, currentContent, currentHeaders);

        return sections;
    }

    /**
     * 将 currentContent 中非空内容提交为一个 PARA Section
     */
    private void finalizeCurrentSection(List<Section> sections,
                                        StringBuilder currentContent,
                                        List<HeaderEntry> currentHeaders) {
        if (currentContent.isEmpty()) return;
        String content = currentContent.toString();
        if (StrUtil.isBlank(content.strip())) return;
        sections.add(new Section(Section.Kind.PARA, content, currentHeaders, 0));
    }

    // ==================== Phase 2: 递归切分超大 Section ====================

    private List<Section> splitOversizedSections(List<Section> sections, int target, int max) {
        List<Section> result = new ArrayList<>();
        int nextGroupId = 1;
        for (Section section : sections) {

            String content = section.content;
            if (section.kind != Section.Kind.PARA || content.length() <= target) {
                result.add(section);
                continue;
            }
            int effectiveTarget = Math.min(content.length(), max);
            effectiveTarget = Math.min(effectiveTarget, target);
            List<String> parts = recursiveSplit(content, SEPARATORS, 0, effectiveTarget);
            int groupId = nextGroupId++;
            for (String part : parts) {
                result.add(new Section(Section.Kind.PARA, part, section.headerPath, groupId));
            }
        }
        return result;
    }

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

    // ==================== Phase 3: 结构感知合并 ====================

    /**
     * 将 Section 列表合并为最终 chunk：
     * - HR 是硬边界，强制切断
     * - 超过 max 强制切断
     * - 超过 target 且当前 >= min 时切断
     * - 合并后取第一个 Section 的 headerPath（最宽标题作用域）
     * - 尾部不足 min 时尝试与前一个合并
     * - 主要目的就是为了合并同一个标题下,多个空行分割的小段落
     */
    private List<MergedSection> mergeSections(List<Section> sections, int target, int min, int max) {
        if (sections.isEmpty()) return List.of();

        List<MergedSection> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<HeaderEntry> currentHeaders = null;
        int currentGroupId = 0;

        for (Section section : sections) {
            String content = section.content;

            // HR 作为硬边界
            if (section.kind == Section.Kind.HR) {
                if (!current.isEmpty() && StrUtil.isNotBlank(current.toString().strip())) {
                    result.add(new MergedSection(current.toString(), currentHeaders,
                            Math.max(currentGroupId, 0)));
                }
                current.setLength(0);
                currentHeaders = null;
                currentGroupId = 0;
                continue;
            }

            // 加入后超过 max → 先提交当前
            if (!current.isEmpty() && current.length() + content.length() > max) {
                result.add(new MergedSection(current.toString(), currentHeaders,
                        Math.max(currentGroupId, 0)));
                current.setLength(0);
                currentHeaders = null;
                currentGroupId = 0;
            }

            // 加入后超过 target 且当前 >= min → 提交当前
            if (current.length() >= min && current.length() + content.length() > target) {
                result.add(new MergedSection(current.toString(), currentHeaders,
                        Math.max(currentGroupId, 0)));
                current.setLength(0);
                currentHeaders = null;
                currentGroupId = 0;
            }

            // 标题是结构边界：提交当前缓冲区，标题本身不作为内容（headerPath 已携带标题信息）
            if (section.kind == Section.Kind.HEADING) {
                if (!current.isEmpty() && StrUtil.isNotBlank(current.toString().strip())) {
                    result.add(new MergedSection(current.toString(), currentHeaders,
                            Math.max(currentGroupId, 0)));
                    current.setLength(0);
                    currentHeaders = null;
                    currentGroupId = 0;
                }
                continue;
            }

            if (current.isEmpty()) {
                currentHeaders = section.headerPath;
                currentGroupId = section.splitGroupId;
            } else if (section.splitGroupId > 0 && section.splitGroupId != currentGroupId) {
                currentGroupId = -1;  // mixed origin → no overlap
            }
            current.append(content);
        }

        // 处理剩余
        if (!current.isEmpty() && StrUtil.isNotBlank(current.toString().strip())) {
            int finalGroupId = Math.max(currentGroupId, 0);
            if (current.length() < min && !result.isEmpty()) {
                MergedSection last = result.remove(result.size() - 1);
                String combined = last.content + current;
                if (combined.length() <= max) {
                    result.add(new MergedSection(combined, last.headerPath, 0));
                } else {
                    result.add(last);
                    result.add(new MergedSection(current.toString(), currentHeaders, finalGroupId));
                }
            } else {
                result.add(new MergedSection(current.toString(), currentHeaders, finalGroupId));
            }
        }

        return result;
    }

    // ==================== Phase 4: 构建 VectorChunk ====================

    private List<VectorChunk> buildChunks(List<MergedSection> merged, int overlap) {
        if (merged.isEmpty()) return List.of();

        List<VectorChunk> chunks = new ArrayList<>();
        String prevTail = null;
        int prevGroupId = 0;

        for (int i = 0; i < merged.size(); i++) {
            MergedSection ms = merged.get(i);
            String content = ms.content;

            // 标题路径拼入 content，确保即使下游未使用 metadata 标题信息也不丢失
            String headerPrefix = buildHeaderPrefix(ms.headerPath);
            String contentWithHeader = headerPrefix.isEmpty() ? content : headerPrefix + content;

            // 仅对同一递归切分组内的相邻 chunk 添加 overlap
            boolean shouldOverlap = overlap > 0
                    && prevTail != null && !prevTail.isEmpty()
                    && prevGroupId > 0
                    && ms.splitGroupId > 0
                    && prevGroupId == ms.splitGroupId;

            String finalContent = shouldOverlap
                    ? prevTail + contentWithHeader
                    : contentWithHeader;

            Map<String, Object> meta = new LinkedHashMap<>();
            if (ms.headerPath != null) {
                for (HeaderEntry entry : ms.headerPath) {
                    meta.put("Header " + entry.level, entry.title);
                }
            }

            chunks.add(VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(i)
                    .content(finalContent)
                    .metadata(meta)
                    .build());

            if (ms.splitGroupId > 0) {
                prevTail = tailByChars(contentWithHeader, overlap);
                prevGroupId = ms.splitGroupId;
            } else {
                prevTail = null;
                prevGroupId = 0;
            }
        }

        return chunks;
    }

    /**
     * 将标题路径格式化为前缀字符串
     * 如 headerPath = [H2: "Potential Issues", H3: "Delayed Reporting"]
     * → "[Potential Issues > Delayed Reporting]\n\n"
     */
    private String buildHeaderPrefix(List<HeaderEntry> headerPath) {
        if (headerPath == null || headerPath.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < headerPath.size(); i++) {
            if (i > 0) sb.append(" > ");
            sb.append(headerPath.get(i).title);
        }
        sb.append("]\n\n");
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    private String tailByChars(String s, int n) {
        if (n <= 0) return "";
        int len = s.length();
        return len <= n ? s : s.substring(len - n);
    }

    private int headingLevel(String strippedLine) {
        int level = 0;
        while (level < strippedLine.length() && strippedLine.charAt(level) == '#') {
            level++;
        }
        return level;
    }
}
