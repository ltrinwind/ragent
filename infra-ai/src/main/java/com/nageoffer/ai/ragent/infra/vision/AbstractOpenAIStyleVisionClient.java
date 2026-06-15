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

package com.nageoffer.ai.ragent.infra.vision;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

/**
 * OpenAI 兼容协议 VisionClient 抽象基类
 * 封装 /v1/chat/completions 多模态协议的通用逻辑，子类只需提供 provider 和覆写钩子方法
 */
@Slf4j
public abstract class AbstractOpenAIStyleVisionClient implements VisionClient {

    protected final OkHttpClient httpClient;

    protected AbstractOpenAIStyleVisionClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ==================== 子类钩子方法 ====================

    /**
     * 是否要求提供商配置 API Key，默认 true
     */
    protected boolean requiresApiKey() {
        return true;
    }

    // ==================== 接口实现 ====================

    @Override
    public String describe(VisionRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.VISION);

        // 构建 OpenAI Vision API 请求体
        JsonObject body = buildVisionRequestBody(request, target);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON));
        if (requiresApiKey()) {
            requestBuilder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        Request httpRequest = requestBuilder.build();

        // 发送请求并解析响应
        JsonObject json;
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errBody = HttpResponseHelper.readBody(response.body());
                log.warn("{} vision 请求失败: status={}, body={}", provider(), response.code(), errBody);
                throw new ModelClientException(
                        provider() + " vision 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            json = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " vision 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 检查错误响应
        if (json.has("error")) {
            JsonObject err = json.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException(
                    provider() + " vision 错误: " + code + " - " + msg,
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }

        // 解析 chat completions 响应格式
        return extractContent(json);
    }

    /**
     * 构建 OpenAI Vision API 请求体
     */
    private JsonObject buildVisionRequestBody(VisionRequest request, ModelTarget target) {
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        // 构建 messages 数组
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");

        // 构建 content 数组（文本 + 图片）
        JsonArray content = new JsonArray();

        // 文本部分
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", request.getPrompt());
        content.add(textPart);

        // 图片部分
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrlObj = new JsonObject();
        imageUrlObj.addProperty("url", request.getBase64DataUri());
        imagePart.add("image_url", imageUrlObj);
        content.add(imagePart);

        userMessage.add("content", content);
        messages.add(userMessage);
        body.add("messages", messages);

        // 可选参数
        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("max_tokens", request.getMaxTokens());
        }

        return body;
    }

    /**
     * 从 chat completions 响应中提取文本内容
     */
    private String extractContent(JsonObject json) {
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException(
                    provider() + " vision 响应中缺少 choices 数组",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            throw new ModelClientException(
                    provider() + " vision 响应中缺少 message 字段",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        String text = message.has("content") ? message.get("content").getAsString() : null;
        if (text == null || text.isBlank()) {
            throw new ModelClientException(
                    provider() + " vision 响应中 content 为空",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return text;
    }
}
