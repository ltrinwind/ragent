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
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 百炼（阿里云 DashScope）rerank 客户端
 * <p>
 * DashScope rerank 请求体为嵌套结构：
 * {@code {model, input:{query, documents}, parameters:{top_n, return_documents}}}。
 * <p>
 * 响应 results 有两种形态（同一 endpoint，按模型不同）：
 * <ul>
 *   <li>qwen3-rerank：扁平位于顶层 {@code results}，即 {@code {object, results:[{index, relevance_score}], ...}}；</li>
 *   <li>gte-rerank-v2 / qwen3-vl-rerank：嵌套在 {@code output.results} 下。</li>
 * </ul>
 * result 元素均含 {@code index} 与 {@code relevance_score}，映射逻辑在基类共享。
 */
@Service
public class BaiLianRerankClient extends AbstractRerankClient {

    public BaiLianRerankClient(OkHttpClient syncHttpClient, AIModelProperties aiModelProperties) {
        super(syncHttpClient, aiModelProperties);
    }

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    protected JsonObject buildRequestBody(String query, List<RetrievedChunk> candidates, int topN, String model) {
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", model);

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);
        return reqBody;
    }

    @Override
    protected JsonArray extractResults(JsonObject respJson) {
        if (respJson == null) {
            throw new ModelClientException(provider() + " rerank 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // gte-rerank-v2 / qwen3-vl-rerank：results 嵌套在 output.results 下
        if (respJson.has("output")) {
            JsonObject output = respJson.getAsJsonObject("output");
            if (output == null || !output.has("results")) {
                throw new ModelClientException(provider() + " rerank 响应缺少 output.results", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            return output.getAsJsonArray("results");
        }
        // qwen3-rerank：results 扁平位于顶层
        if (respJson.has("results")) {
            return respJson.getAsJsonArray("results");
        }
        throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
    }
}
