# 多模态图片解析现状

本仓库当前采用 upstream 对图片理解的实现方式：独立图片文件在解析阶段进入 `ImageDocumentParser`，由 `VlmService` / `RoutingVlmService` 调用 `ai.vlm` 模型组生成描述文本，再交给 block-aware chunker 和普通 embedding 链路入库。

## 当前链路

1. `DocumentParserSelector` 根据 MIME 将 PNG/JPG/SVG 路由到 `ImageDocumentParser`。
2. SVG 先栅格化为 PNG，其他图片保留原始字节。
3. `RoutingVlmService` 通过 provider 的 chat-compatible endpoint 发送 OpenAI 风格多模态消息。
4. `ImageDocumentParser` 上传原图资产，构造带描述和 `AssetRef` 的 `ImageBlock`。
5. `ImageChunker` 使用描述作为 `content` / `embeddingText`，同时保留 `contentType`、`imageUrl`、`imageMimeType`、`assets`。
6. 检索命中 IMAGE chunk 时，fork 保留的 retrieval context / SSE context / 前端参考来源面板负责展示图片来源。

## 配置

- 模型路由使用 `ai.vlm`。
- 图片解析业务参数使用 `rag.image-parse`。
- provider 端点复用 `endpoints.chat`。

## 保留差异

fork 继续保留结构化引用来源能力：`RetrievedContextItem`、`RetrievedChunk` 图片字段、`CONTEXT` SSE 事件、消息 `contexts` 持久化，以及前端参考来源面板。

## 已移除的旧方案

早期 fork 曾在摄取流水线中增加单独的图片描述阶段，并维护另一套视觉模型客户端。该方案已被 upstream 的解析期 VLM 方案取代，后续开发应基于本文件描述的链路。
