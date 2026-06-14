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

package com.nageoffer.ai.ragent.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HTTP 重排客户端基类
 * <p>
 * 封装各 rerank provider 的公共流程：候选去重、topN 守卫、HTTP 调用、
 * 以及把响应 results 按 index 映射回 {@link RetrievedChunk} 并以 relevance_score 重排。
 * <p>
 * 子类只需实现两个差异点：
 * <ul>
 *   <li>{@link #buildRequestBody}：构造各 provider 特有的请求体（百炼为嵌套 input/parameters，
 *       SiliconFlow 为扁平 Cohere 风格）。</li>
 *   <li>{@link #extractResults}：从响应中取出 results 数组（百炼位于 output.results 下，
 *       SiliconFlow 位于顶层 results）。</li>
 * </ul>
 * SiliconFlow 与百炼的 results 元素均含 {@code index} 与 {@code relevance_score}，
 * 故结果映射逻辑可在基类共享。
 */
@Slf4j
public abstract class AbstractRerankClient implements RerankClient {

    protected final OkHttpClient httpClient;

    protected AbstractRerankClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        String model = HttpResponseHelper.requireModel(target, provider());

        JsonObject reqBody = buildRequestBody(query, candidates, topN, model);

        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        JsonArray results = extractResults(respJson);
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        return mapResults(candidates, results, topN);
    }

    /**
     * 构造各 provider 特有的 rerank 请求体。
     *
     * @param model 已解析的模型 ID
     */
    protected abstract JsonObject buildRequestBody(String query, List<RetrievedChunk> candidates, int topN, String model);

    /**
     * 从响应 JSON 中提取 results 数组，校验失败时抛 {@link ModelClientException}。
     */
    protected abstract JsonArray extractResults(JsonObject respJson);

    /**
     * 按 index 把响应结果映射回原候选，填充 relevance_score，不足 topN 时按原顺序回填。
     * SiliconFlow 与百炼的 result 元素均含 index 与 relevance_score，故可共享。
     */
    private List<RetrievedChunk> mapResults(List<RetrievedChunk> candidates, JsonArray results, int topN) {
        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk src = candidates.get(idx);

            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            RetrievedChunk hit = score != null ? RetrievedChunk.builder()
                    .id(src.getId())
                    .text(src.getText())
                    .score(score)
                    .contentType(src.getContentType())
                    .imageUrl(src.getImageUrl())
                    .imageMimeType(src.getImageMimeType())
                    .build() : src;
            reranked.add(hit);
            addedIds.add(src.getId());

            if (reranked.size() >= topN) {
                break;
            }
        }

        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }
}
