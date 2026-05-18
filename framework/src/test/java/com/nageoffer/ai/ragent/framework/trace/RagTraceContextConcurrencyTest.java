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

package com.nageoffer.ai.ragent.framework.trace;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 TTL 浅拷贝竞态条件 bug。
 *
 * 关键：必须用 TtlExecutors.getTtlExecutorService() 包装线程池，
 * TTL 才会在任务提交时把父线程的值 copy 到子线程。
 * 否则子线程拿到 null，各自创建独立的 ArrayDeque，永远测不出 bug。
 *
 * 旧代码（无 copy 覆写）：TTL 透传时浅拷贝，多个子线程共享同一个 ArrayDeque 引用，
 * 并发 pop 时触发 NoSuchElementException（TOCTOU：isEmpty() 通过但 pop() 时已空）。
 *
 * 修复后（copy 覆写为深拷贝）：每个子线程持有独立的 ArrayDeque 副本，互不影响。
 */
class RagTraceContextConcurrencyTest {

    @AfterEach
    void tearDown() {
        RagTraceContext.clear();
    }


    /**
     * 模拟 RetrievalEngine 场景：一个父节点下并行处理多个子问题，
     * 每个子问题内部再嵌套 push/pop。
     */
    @Test
    void simulateRetrievalEngine_subQuestions_shouldBeIsolated() throws Exception {
        int subQuestionCount = 3;

        RagTraceContext.setTraceId("trace-retrieval");
        RagTraceContext.pushNode("RetrievalEngine.retrieve");

        ExecutorService pool = TtlExecutors.getTtlExecutorService(
                Executors.newFixedThreadPool(subQuestionCount));
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(subQuestionCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < subQuestionCount; i++) {
            final String subQ = "sub-question-" + i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    // TTL 透传后，每个线程应拿到独立的栈副本
                    RagTraceContext.pushNode(subQ + ":retrieveAndRerank");
                    RagTraceContext.pushNode(subQ + ":multiChannelRetrieval");
                    RagTraceContext.popNode(); // pop multiChannelRetrieval
                    RagTraceContext.popNode(); // pop retrieveAndRerank
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "Sub-questions did not finish in time");
        pool.shutdown();

        assertTrue(errors.isEmpty(),
                "Sub-question processing had errors: " + errors);
        // 父线程栈仍完好
        assertEquals("RetrievalEngine.retrieve", RagTraceContext.currentNodeId());
        assertEquals(1, RagTraceContext.depth());

        RagTraceContext.clear();
    }

    /**
     * 压测：高并发下反复 push/pop，验证无异常、无泄漏。
     */
    @Test
    void stressTest_manyThreadsManyRounds() throws Exception {
        int threads = 8;
        int roundsPerThread = 200;

        RagTraceContext.setTraceId("stress-trace");
        RagTraceContext.pushNode("root");

        ExecutorService pool = TtlExecutors.getTtlExecutorService(
                Executors.newFixedThreadPool(threads));
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> futures = IntStream.range(0, threads)
                .mapToObj(t -> CompletableFuture.runAsync(() -> {
                    for (int r = 0; r < roundsPerThread; r++) {
                        try {
                            RagTraceContext.pushNode("node-" + t + "-" + r);
                            RagTraceContext.popNode();
                        } catch (Throwable e) {
                            errors.add(e);
                        }
                    }
                }, pool))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(errors.isEmpty(),
                "Stress test errors (" + errors.size() + "): " + errors);
        assertEquals(1, RagTraceContext.depth());
        assertEquals("root", RagTraceContext.currentNodeId());

        RagTraceContext.clear();
    }
}
