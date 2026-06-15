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

import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式视觉理解服务实现类
 * <p>
 * 该服务通过模型路由机制动态选择合适的视觉模型客户端，
 * 并支持失败降级策略。自动在配置的视觉模型之间进行故障转移
 */
@Service
@Primary
public class RoutingVisionService implements VisionService {

    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, VisionClient> clientsByProvider;

    public RoutingVisionService(
            ModelSelector selector,
            ModelRoutingExecutor executor,
            List<VisionClient> clients) {
        this.selector = selector;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(VisionClient::provider, Function.identity()));
    }

    @Override
    public String describe(VisionRequest request) {
        return executor.executeWithFallback(
                ModelCapability.VISION,
                selector.selectVisionCandidates(),
                this::resolveClient,
                (client, target) -> client.describe(request, target)
        );
    }

    @Override
    public String describe(VisionRequest request, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.VISION,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.describe(request, target)
        );
    }

    private VisionClient resolveClient(ModelTarget target) {
        return clientsByProvider.get(target.candidate().getProvider());
    }

    private ModelTarget resolveTarget(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new RemoteException("Vision 模型ID不能为空");
        }
        return selector.selectVisionCandidates().stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Vision 模型不可用: " + modelId));
    }
}
