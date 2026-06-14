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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SiliconFlow（硅基流动）rerank 客户端
 * <p>
 * SiliconFlow rerank 接口为 Cohere 风格的扁平结构：
 * <pre>
 * POST /v1/rerank   (base: https://api.siliconflow.cn)
 * {
 *   "model": "BAAI/bge-reranker-v2-m3",
 *   "query": "...",
 *   "documents": ["...", "..."],
 *   "return_documents": true,
 *   "top_n": 4
 * }
 * </pre>
 * 响应 results 位于顶层：{@code {results:[{index, relevance_score, document}], meta:{...}}}。
 * <p>
 * 文档：<a href="https://api-docs.siliconflow.cn/docs/api/rerank-post">SiliconFlow Rerank API</a>
 */
@Service
public class SiliconFlowRerankClient extends AbstractRerankClient {

    public SiliconFlowRerankClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    protected JsonObject buildRequestBody(String query, List<RetrievedChunk> candidates, int topN, String model) {
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", model);
        reqBody.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        reqBody.add("documents", documentsArray);
        reqBody.addProperty("return_documents", true);
        reqBody.addProperty("top_n", topN);
        return reqBody;
    }

    @Override
    protected JsonArray extractResults(JsonObject respJson) {
        if (respJson == null || !respJson.has("results")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return respJson.getAsJsonArray("results");
    }
}
