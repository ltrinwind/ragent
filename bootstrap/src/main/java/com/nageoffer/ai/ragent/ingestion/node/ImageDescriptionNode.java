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

package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.image.ImageProcessingService;
import com.nageoffer.ai.ragent.core.parser.ExtractedImage;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.rag.config.RagMultimodalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片描述摄取节点
 * <p>
 * 从 IngestionContext 中读取提取的嵌入图片，
 * 上传到对象存储，调用 VLM 生成文本描述，
 * 构造 contentType=IMAGE 的 VectorChunk 并追加到 context.chunks。
 * <p>
 * Pipeline 配置中需显式包含此节点，不配置则不执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageDescriptionNode implements IngestionNode {

    private final ImageProcessingService imageProcessingService;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final RagMultimodalProperties multimodalProperties;

    @Override
    public String getNodeType() {
        return IngestionNodeType.IMAGE_DESCRIPTION.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 1. 检查是否启用多模态
        if (!multimodalProperties.isEnabled()) {
            return NodeResult.skip("multimodal disabled");
        }

        // 2. 检查是否有图片需要处理
        List<ExtractedImage> images = context.getExtractedImages();
        if (images == null || images.isEmpty()) {
            return NodeResult.ok("无图片需要处理");
        }

        // 3. 确定图片 chunk 的起始 index
        List<VectorChunk> current = context.getChunks();
        int baseIndex = (current == null) ? 0 : current.size();

        // 4. 获取 bucket name（使用 vectorSpaceId 的 logicalName，与文档上传一致）
        String bucketName = context.getVectorSpaceId() != null
                ? context.getVectorSpaceId().getLogicalName()
                : "knowledge";

        // 5. 处理图片
        ImageProcessingService.ProcessResult result = imageProcessingService.processImages(images, baseIndex, bucketName);
        List<VectorChunk> imageChunks = result.chunks();

        if (imageChunks.isEmpty()) {
            return NodeResult.ok(String.format("处理图片 %d 张，全部跳过或失败", images.size()));
        }

        // 6. 生成 embedding
        chunkEmbeddingService.embed(imageChunks, context.getEmbeddingModel());

        // 7. 追加图片 chunk 到 context
        if (context.getChunks() == null) {
            context.setChunks(new ArrayList<>(imageChunks));
        } else {
            context.getChunks().addAll(imageChunks);
        }

        // 8. 返回结果
        String msg = String.format("处理图片 %d 张，成功 %d，失败 %d，跳过 %d",
                images.size(), result.success(), result.failed(), result.skipped());
        return NodeResult.ok(msg);
    }
}
