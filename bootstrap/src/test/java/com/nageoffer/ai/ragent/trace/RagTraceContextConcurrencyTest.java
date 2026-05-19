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

package com.nageoffer.ai.ragent.trace;

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 复现 RagTraceContext 中 NODE_STACK (TransmittableThreadLocal<Deque>)
 * 在并发场景下因浅拷贝导致的 depth 不一致
 * <p>
 * 模拟场景：intent-resolve 下两个子问题并行执行，
 * 每个子问题进入 llm-chat-routing → bailian-chat 两层嵌套。
 * <p>
 * 期望行为：每个子线程的 depth 链路应该独立，bailian-chat 层的 depth 应一致为 2。
 * 实际行为（浅拷贝 bug）：多个子线程共享同一个 Deque 实例，depth 会互相干扰。
 */
@DisplayName("RagTraceContext TTL 浅拷贝并发 Bug 复现")
class RagTraceContextConcurrencyTest {

    /**
     * 使用 TtlExecutors 包装线程池，与生产环境 ThreadPoolExecutorConfig 一致
     */
    private final Executor ttlExecutor = TtlExecutors.getTtlExecutor(
            Executors.newFixedThreadPool(4));

    @AfterEach
    void cleanup() {
        RagTraceContext.clear();
    }

    /**
     * 复现 Bug：多子问题并行时 bailian-chat 层的 depth 不一致
     * 前提: RagTraceContext 中 NODE_STACK 为原来的浅拷贝实现
     * <p>
     * 模拟 intent-resolve 的执行流程：
     * 父线程: setTraceId → pushNode(intent-resolve)
     * 子线程A: pushNode(routing_A) → 读 depth → pushNode(chat_A) → 读 depth → pop → pop
     * 子线程B: pushNode(routing_B) → 读 depth → pushNode(chat_B) → 读 depth → pop → pop
     * <p>
     * 浅拷贝时子线程共享同一个 Deque，push/pop 互相干扰，
     * 导致 chat 层读到的 depth 不总是 2。
     */
    @RepeatedTest(5)
    @DisplayName("Bug1: 多子问题 depth 不一致")
    void shouldHaveConsistentDepth_butShallowCopyCauseDrift() {
        // ---- 模拟父线程：进入 intent-resolve（depth=0 时 push，之后 depth=1）----
        RagTraceContext.setTraceId("test-trace-001");
        String intentNodeId = "node-intent-resolve";
        RagTraceContext.pushNode(intentNodeId);
        assertEquals(1, RagTraceContext.depth(), "intent-resolve push 后 depth 应为 1");

        int subQuestionCount = 2;
        CountDownLatch startGate = new CountDownLatch(1);
        CopyOnWriteArrayList<Integer> routingDepths = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Integer> chatDepths = new CopyOnWriteArrayList<>();

        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < subQuestionCount; i++) {
            final int index = i;
            tasks.add(CompletableFuture.runAsync(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // ---- 模拟 llm-chat-routing 层 ----
                int depthBeforeRouting = RagTraceContext.depth();
                routingDepths.add(depthBeforeRouting);
                String routingNodeId = "node-routing-" + index;
                RagTraceContext.pushNode(routingNodeId);

                // 模拟一点处理时间，增加交错概率
                sleepQuietly(5);

                // ---- 模拟 bailian-chat 层 ----
                int depthBeforeChat = RagTraceContext.depth();
                chatDepths.add(depthBeforeChat);
                String chatNodeId = "node-chat-" + index;
                RagTraceContext.pushNode(chatNodeId);

                sleepQuietly(10); // 模拟 LLM 调用

                // ---- 退出 bailian-chat ----
                RagTraceContext.popNode();
                // ---- 退出 llm-chat-routing ----
                RagTraceContext.popNode();

            }, ttlExecutor));
        }

        // 释放所有子线程
        startGate.countDown();
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        // ---- 断言：如果 TTL 做了深拷贝，每个子线程的 depth 应该完全独立 ----
        // routing 层 depth 应全部为 1（intent-resolve 栈里 1 个元素）
        // chat 层 depth 应全部为 2（intent-resolve + routing 栈里 2 个元素）

        System.out.println("routing depths: " + routingDepths);
        System.out.println("chat depths:    " + chatDepths);

        boolean allRoutingCorrect = routingDepths.stream().allMatch(d -> d == 1);
        boolean allChatCorrect = chatDepths.stream().allMatch(d -> d == 2);

        if (!allRoutingCorrect || !allChatCorrect) {
            StringBuilder msg = new StringBuilder("[BUG 复现成功] depth 偏离预期!\n");
            if (!allRoutingCorrect) {
                msg.append("  routing depth: 期望全部=1, 实际=").append(routingDepths).append("\n");
            }
            if (!allChatCorrect) {
                msg.append("  chat depth: 期望全部=2, 实际=").append(chatDepths).append("\n");
            }
            System.err.print(msg);
            fail(msg.toString());
        }
    }


    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
