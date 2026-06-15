# 多模态文档嵌入支持实施方案

## 目标与范围

- 仅支持 **Pipeline 模式**识别文档图片。
- 暂不改造普通 **Chunk 模式**，`runChunkProcess()` 保持现状。
- 暂不适配 **Milvus**，当前多模态能力以 `rag.vector.type=pg` 为前提。
- 图片通过 VLM 生成文本描述，描述作为图片 chunk 的 `content` 参与 embedding 与检索。
- VLM 输入固定使用 **base64 data URI**，不依赖对象存储公网或内网可达性。
- 图片持久化仍保存到 RustFS/S3，`t_knowledge_chunk.image_url` 保存内部对象地址。
- 前端访问图片通过后端代理接口读取对象存储，不直接访问 `s3://...`。
- **视觉模型独立管理**：参照 embedding/rerank 模式，新增 `VisionClient` + `RoutingVisionService` + `ai.vision` 模型组，不修改现有 ChatMessage/ChatClient。

Pipeline 需要显式配置：

```text
parser -> chunker -> image_description -> indexer
```

不配置 `image_description` 的 Pipeline 行为保持不变。

## 总体流程

1. `ParserNode` 解析文档文本，同时提取嵌入图片到 `IngestionContext.extractedImages`。
2. `ChunkerNode` 对文本分块并生成文本 chunk embedding。
3. `ImageDescriptionNode` 读取提取图片，上传图片到对象存储，用 base64 data URI 调 VLM 生成描述。
4. `ImageDescriptionNode` 将描述构造成 `contentType=IMAGE` 的 `VectorChunk`，生成 embedding 后追加到 `context.chunks`。
5. `IndexerNode` 写入 PgVector；业务持久化将图片 chunk 写入 `t_knowledge_chunk`。
6. `PgRetrieverService` 检索 `t_knowledge_vector` 后 JOIN `t_knowledge_chunk` 返回图片 URL。

## 阶段一：视觉模型服务（infra-ai）

参照现有 embedding/rerank 的完整模式，新增 vision 模型服务：

```text
ChatClient      → RoutingLLMService       → ai.chat ModelGroup
EmbeddingClient → RoutingEmbeddingService  → ai.embedding ModelGroup
RerankClient    → RoutingRerankService     → ai.rerank ModelGroup
VisionClient    → RoutingVisionService     → ai.vision ModelGroup   ← 新增
```

### 1. VisionClient 接口

新增：`infra-ai/.../infra/vision/VisionClient.java`

```java
public interface VisionClient {
    String provider();
    String describe(VisionRequest request, ModelTarget target);
}
```

### 2. VisionRequest DTO

新增：`infra-ai/.../infra/vision/VisionRequest.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VisionRequest {
    private String prompt;
    private String base64DataUri;   // data:{mimeType};base64,{payload}
    private Double temperature;
    private Integer maxTokens;
}
```

### 3. VisionService 接口

新增：`infra-ai/.../infra/vision/VisionService.java`

```java
public interface VisionService {
    String describe(VisionRequest request);
    String describe(VisionRequest request, String modelId);
}
```

### 4. AbstractOpenAIStyleVisionClient

新增：`infra-ai/.../infra/vision/AbstractOpenAIStyleVisionClient.java`

参照 `AbstractOpenAIStyleEmbeddingClient`：
- 复用 `OkHttpClient`、`Gson`
- 复用 `HttpResponseHelper`、`ModelUrlResolver`、`HttpMediaTypes`
- 只有同步调用（VLM 不需要流式）
- 请求体为 OpenAI Vision API 格式：

```json
{
  "model": "qwen-vl-max",
  "messages": [{
    "role": "user",
    "content": [
      { "type": "text", "text": "请描述图片..." },
      { "type": "image_url", "image_url": { "url": "data:image/png;base64,..." } }
    ]
  }]
}
```

模板方法：`doDescribe(VisionRequest, ModelTarget)`。

### 5. Provider 实现类

新增（非常薄，只提供 `provider()` 名）：

```text
infra-ai/.../infra/vision/BailianVisionClient.java
infra-ai/.../infra/vision/OllamaVisionClient.java
infra-ai/.../infra/vision/SiliconFlowVisionClient.java
infra-ai/.../infra/vision/AIHubMixVisionClient.java
```

### 6. RoutingVisionService

新增：`infra-ai/.../infra/vision/RoutingVisionService.java`

参照 `RoutingEmbeddingService`，复用 `ModelRoutingExecutor` + `ModelHealthStore`：

```java
@Service @Primary
public class RoutingVisionService implements VisionService {
    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, VisionClient> clientsByProvider;

    @Override
    public String describe(VisionRequest request) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectVisionCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.describe(request, target)
        );
    }

    @Override
    public String describe(VisionRequest request, String modelId) {
        // 指定模型路由，类似 RoutingLLMService.chat(request, modelId)
    }
}
```

### 7. AIModelProperties 加 vision ModelGroup

修改：`infra-ai/.../infra/config/AIModelProperties.java`

增加与 `chat`/`embedding`/`rerank` 平级的字段：

```java
private ModelGroup vision;
```

### 8. ModelSelector 加 selectVisionCandidates

修改：`infra-ai/.../infra/model/ModelSelector.java`

```java
public List<ModelTarget> selectVisionCandidates() {
    return selectCandidates(properties.getVision());
}
```

复用已有的 `selectCandidates(ModelGroup)` 私有方法。

### 9. application.yaml 配置

修改：`bootstrap/src/main/resources/application.yaml`

各 provider 加 vision endpoint（与 chat 端点相同）：

```yaml
ai:
  providers:
    bailian:
      endpoints:
        chat: /compatible-mode/v1/chat/completions
        vision: /compatible-mode/v1/chat/completions
```

新增独立的 vision 模型组：

```yaml
ai:
  vision:
    default-model: qwen-vl-max
    candidates:
      - id: qwen-vl-max
        provider: bailian
        model: qwen-vl-max
        priority: 1
      - id: qwen2.5-vl-local
        provider: ollama
        model: qwen2.5vl:7b
        priority: 2
```

### 10. RagMultimodalProperties

新增：`bootstrap/.../rag/config/RagMultimodalProperties.java`

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.multimodal")
public class RagMultimodalProperties {
    private boolean enabled = false;
    private int maxImagesPerDocument = 50;
    private int imageMinSizeKb = 5;
    private int maxImageBytes = 4194304;
    private int vlmConcurrency = 4;
    private int vlmTimeoutSeconds = 60;
}
```

模型由 `ai.vision` 管理，不再需要 `image-description-model` 字段。

```yaml
rag:
  multimodal:
    enabled: true
    max-images-per-document: 50
    image-min-size-kb: 5
    max-image-bytes: 4194304
    vlm-concurrency: 4
    vlm-timeout-seconds: 60
```

`vlm-concurrency` 和 `vlm-timeout-seconds` V1 先预留字段，后续再接入并发和超时控制。

## 阶段二：数据模型扩展

### 1. ParseResult 增加图片结果

修改：`bootstrap/.../core/parser/ParseResult.java`

```java
public record ParseResult(
        String text,
        Map<String, Object> metadata,
        List<ExtractedImage> images
) {
    public static ParseResult ofText(String text) {
        return new ParseResult(text, Map.of(), List.of());
    }

    public static ParseResult of(String text, Map<String, Object> metadata) {
        return new ParseResult(text, metadata != null ? metadata : Map.of(), List.of());
    }

    public static ParseResult of(String text, Map<String, Object> metadata, List<ExtractedImage> images) {
        return new ParseResult(text, metadata != null ? metadata : Map.of(), images != null ? images : List.of());
    }
}
```

### 2. ExtractedImage

新增：`bootstrap/.../core/parser/ExtractedImage.java`

```java
public record ExtractedImage(
        int index,
        byte[] data,
        String mimeType
) {
}
```

V1 不在 `ExtractedImage` 中保存页码、宽高。Tika 嵌入资源 metadata 不保证提供这些信息，后续再作为增强项补充。

### 3. VectorChunk 加字段

修改：`bootstrap/.../core/chunk/VectorChunk.java`

```java
@Builder.Default private String contentType = "TEXT";
private String imageUrl;
private String imageMimeType;
```

### 4. IngestionContext 加字段

修改：`bootstrap/.../ingestion/domain/context/IngestionContext.java`

```java
private List<ExtractedImage> extractedImages;
private String embeddingModel;
```

### 5. IngestionNodeType 加枚举值

修改：`bootstrap/.../ingestion/domain/enums/IngestionNodeType.java`

```java
IMAGE_DESCRIPTION("image_description")
```

### 6. RetrievedChunk 加字段

修改：`framework/.../convention/RetrievedChunk.java`

```java
private String contentType;
private String imageUrl;
private String imageMimeType;
```

### 7. 持久化模型加字段

修改：

```text
bootstrap/.../knowledge/dao/entity/KnowledgeChunkDO.java
bootstrap/.../knowledge/controller/request/KnowledgeChunkCreateRequest.java
bootstrap/.../knowledge/controller/vo/KnowledgeChunkVO.java
```

新增：

```java
private String contentType;
private String imageUrl;
private String imageMimeType;
```

`VectorChunk.contentType` 默认值：

```java
@Builder.Default private String contentType = "TEXT";
```

## 阶段三：Tika 图片提取

修改：`bootstrap/.../core/parser/TikaDocumentParser.java`

注意点：

- 不能只创建 `PDFParserConfig` 后调用 `TIKA.parseToString()`。
- 需要使用 `AutoDetectParser` 或底层 `Parser.parse()`。
- 需要将 `PDFParserConfig` 放入 `ParseContext`。
- 需要自定义 `EmbeddedDocumentExtractor` 收集图片。

核心要求：

```java
PDFParserConfig pdfConfig = new PDFParserConfig();
pdfConfig.setExtractInlineImages(true);
pdfConfig.setExtractUniqueInlineImagesOnly(true);

ParseContext parseContext = new ParseContext();
parseContext.set(PDFParserConfig.class, pdfConfig);
parseContext.set(EmbeddedDocumentExtractor.class, customExtractor);
```

过滤策略：

- 按 `image-min-size-kb` 过滤小图。
- 按图片 hash 去重。
- 按 `max-images-per-document` 截断。
- V1 不依赖宽高过滤。
- mimeType 优先通过 Tika metadata 检测，为空时用 `image/png` 兜底。

`extractText(InputStream, String)` 保持原行为，避免影响 Chunk 模式。

## 阶段四：图片处理服务

### 1. ImageDescriptionService

新增：`bootstrap/.../core/image/ImageDescriptionService.java`

依赖 `VisionService`（走完整路由/熔断/降级）：

```java
@Service
public class ImageDescriptionService {
    private final VisionService visionService;

    public String describe(byte[] imageData, String mimeType) {
        String dataUri = "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(imageData);
        VisionRequest request = VisionRequest.builder()
                .prompt("请用中文描述图片中的关键信息。若是图表，请提取标题、坐标轴、图例、关键数值、趋势、异常点和结论。不要编造图片中不存在的数据。")
                .base64DataUri(dataUri)
                .temperature(0.3)
                .build();
        return visionService.describe(request);
    }
}
```

VLM 挂了自动切备用模型，不需要构造 ChatMessage 或 ContentPart。

base64 注意事项：

- base64 内容只用于调用 VLM，不写入数据库。
- base64 内容不得打印到日志。
- 图片超过 `max-image-bytes` 时跳过；V1 先跳过。
- `mimeType` 为空时优先通过 Tika 重新检测。

### 2. ImageProcessingService

新增：`bootstrap/.../core/image/ImageProcessingService.java`

职责：

- 遍历 `ExtractedImage`。
- 应用数量、大小、去重过滤。
- 直接调用 `FileStorageService.upload(bucketName, byte[] content, String originalFilename, String contentType)` 上传图片。
- 调用 `ImageDescriptionService` 生成描述。
- 构造图片 `VectorChunk`。

单张图片失败不阻断其他图片，记录 warn 后跳过。

`ImageStorageService` 不需要新增。图片存储没有额外业务抽象，`ImageProcessingService` 直接依赖已有 `FileStorageService` 即可。

失败与统计要求：

- 记录成功图片数、失败图片数、跳过图片数。
- 单张失败 `log.warn`，汇总写入 `NodeResult.message`。
- `vlm-concurrency` 和 `vlm-timeout-seconds` V1 先作为配置字段保留。

图片 chunk：

```java
VectorChunk.builder()
        .chunkId(IdUtil.getSnowflakeNextIdStr())  // 分布式 ID
        .index(baseIndex + i)
        .contentType("IMAGE")
        .imageUrl(internalImageUrl)
        .imageMimeType(mimeType)
        .content("【图片描述】第 " + imageIndex + " 张图：" + description)
        .metadata(Map.of(
                "imageIndex", imageIndex,
                "mimeType", mimeType
        ))
        .build();
```

## 阶段五：ImageDescriptionNode

修改枚举：

```text
bootstrap/.../ingestion/domain/enums/IngestionNodeType.java
```

新增：

```java
IMAGE_DESCRIPTION("image_description")
```

新增节点：

```text
bootstrap/.../ingestion/node/ImageDescriptionNode.java
```

执行逻辑：

```java
@Component
@RequiredArgsConstructor
public class ImageDescriptionNode implements IngestionNode {
    private final ImageProcessingService imageProcessingService;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final RagMultimodalProperties multimodalProperties;

    getNodeType() → "image_description"

    execute(context, config):
      1. 检查 multimodalProperties.isEnabled() → 否则 return NodeResult.skip("multimodal disabled")
      2. 取 context.getExtractedImages() → null/空则 return NodeResult.ok("无图片需要处理")
      3. List<VectorChunk> current = context.getChunks()
      4. int baseIndex = current == null ? 0 : current.size()
      5. List<VectorChunk> imageChunks = imageProcessingService.processImages(images, baseIndex)
      6. chunkEmbeddingService.embed(imageChunks, context.getEmbeddingModel())
      7. 追加 imageChunks 到 context.getChunks()
      8. return NodeResult.ok("处理图片 N 张，成功 X，失败 Y，跳过 Z")
```

`NodeOutputExtractor` 增加 `IMAGE_DESCRIPTION` 分支，输出 `imageChunkCount` 和 `totalChunkCount`。

## 阶段六：Pipeline 模式 embeddingModel 透传

修改：`bootstrap/.../knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

仅改 `runPipelineProcess()`，不改 `runChunkProcess()`。

构建 `IngestionContext` 时增加：

```java
.embeddingModel(kbDO.getEmbeddingModel())
```

修改：`bootstrap/.../ingestion/node/ChunkerNode.java`

将：

```java
chunkEmbeddingService.embed(postResult.getChunksToEmbed(), null);
```

改为：

```java
chunkEmbeddingService.embed(postResult.getChunksToEmbed(), context.getEmbeddingModel());
```

null 时 `ChunkEmbeddingService` 仍用默认模型，完全向后兼容。

## 阶段七：数据库与持久化

### 1. SQL

修改：`resources/database/schema_pg.sql`

新增 upgrade SQL：`resources/database/upgrade_v1.3_to_v1.4.sql`

```sql
ALTER TABLE t_knowledge_chunk
  ADD COLUMN IF NOT EXISTS content_type VARCHAR(20) NOT NULL DEFAULT 'TEXT';

ALTER TABLE t_knowledge_chunk
  ADD COLUMN IF NOT EXISTS image_url TEXT;

ALTER TABLE t_knowledge_chunk
  ADD COLUMN IF NOT EXISTS image_mime_type VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_content_type
  ON t_knowledge_chunk(content_type);

COMMENT ON COLUMN t_knowledge_chunk.content_type IS '内容类型：TEXT/IMAGE';
COMMENT ON COLUMN t_knowledge_chunk.image_url IS '图片对象存储地址';
COMMENT ON COLUMN t_knowledge_chunk.image_mime_type IS '图片 MIME 类型';
```

### 2. 映射

修改：

```text
bootstrap/.../knowledge/service/impl/KnowledgeDocumentServiceImpl.java
bootstrap/.../knowledge/service/impl/KnowledgeChunkServiceImpl.java
```

`persistChunksAndVectorsAtomically()` 映射：

```java
req.setContentType(vc.getContentType());
req.setImageUrl(vc.getImageUrl());
req.setImageMimeType(vc.getImageMimeType());
```

`KnowledgeChunkServiceImpl.create()` 和 `batchCreate()` 写入：

```java
.contentType(StringUtils.hasText(request.getContentType()) ? request.getContentType() : "TEXT")
.imageUrl(request.getImageUrl())
.imageMimeType(request.getImageMimeType())
```

## 阶段八：PgVector 检索 JOIN t_knowledge_chunk

### 1. RetrievedChunk 扩展

修改：`framework/.../convention/RetrievedChunk.java`

新增：

```java
private String contentType;
private String imageUrl;
private String imageMimeType;
```

`imageUrl` 是后端内部对象地址，不建议前端直接使用。

### 2. PgRetrieverService 向量检索 JOIN

修改：`bootstrap/.../rag/core/retrieve/PgRetrieverService.java`

SQL 改为：

```sql
SELECT
  v.id,
  v.content,
  c.content_type,
  c.image_url,
  c.image_mime_type,
  1 - (v.embedding <=> ?::vector) AS score
FROM t_knowledge_vector v
JOIN t_knowledge_chunk c ON c.id = v.id
WHERE v.metadata->>'collection_name' = ?
  AND c.enabled = 1
  AND c.deleted = 0
ORDER BY v.embedding <=> ?::vector
LIMIT ?
```

RowMapper：

```java
RetrievedChunk.builder()
        .id(rs.getString("id"))
        .text(rs.getString("content"))
        .contentType(rs.getString("content_type"))
        .imageUrl(rs.getString("image_url"))
        .imageMimeType(rs.getString("image_mime_type"))
        .score(rs.getFloat("score"))
        .build();
```

### 3. BM25 检索也补字段

修改：`bootstrap/.../knowledge/dao/mapper/KnowledgeChunkMapper.java`

`searchByKeyword()` SELECT 增加：

```sql
content_type, image_url, image_mime_type
```

`PgRetrieverService.retrieveByKeyword()` 同步映射到 `RetrievedChunk`。

## 不做事项

- 不重构 `runChunkProcess()`。
- 不让 Chunk 模式自动支持识图。
- 不适配 Milvus metadata 和 Milvus retriever。
- 不在 Chat 回答阶段再次把原图发给 LLM。
- 不做真正的视觉 embedding，只做 VLM 描述文本 embedding。
- 不让前端直接访问 `s3://` 内部对象地址。
- 不修改 ChatMessage、AbstractOpenAIStyleChatClient。
- 图片代理接口暂不实现。

## 文件改动汇总

### 新增文件

```text
infra-ai/.../infra/vision/
├── VisionClient.java                        ← 接口
├── VisionRequest.java                       ← 请求 DTO
├── VisionService.java                       ← 服务接口
├── AbstractOpenAIStyleVisionClient.java      ← 基类
├── RoutingVisionService.java                 ← 路由服务
├── BailianVisionClient.java                  ← provider 实现
├── OllamaVisionClient.java
├── SiliconFlowVisionClient.java
└── AIHubMixVisionClient.java

bootstrap/.../core/parser/ExtractedImage.java
bootstrap/.../core/image/ImageDescriptionService.java
bootstrap/.../core/image/ImageProcessingService.java
bootstrap/.../ingestion/node/ImageDescriptionNode.java
bootstrap/.../rag/config/RagMultimodalProperties.java
resources/database/upgrade_v1.3_to_v1.4.sql
```

### 修改文件

| 模块 | 文件 | 改动 |
|------|------|------|
| infra-ai | AIModelProperties.java | 加 `vision` ModelGroup |
| infra-ai | ModelSelector.java | 加 `selectVisionCandidates()` |
| framework | RetrievedChunk.java | 加 contentType/imageUrl/imageMimeType |
| bootstrap | VectorChunk.java | 加 contentType/imageUrl/imageMimeType |
| bootstrap | ParseResult.java | record 加 images 字段 |
| bootstrap | IngestionContext.java | 加 extractedImages/embeddingModel |
| bootstrap | IngestionNodeType.java | 加 IMAGE_DESCRIPTION 枚举 |
| bootstrap | TikaDocumentParser.java | Parser API + 图片提取 |
| bootstrap | ParserNode.java | 写 images 到 context |
| bootstrap | ChunkerNode.java | 读 embeddingModel |
| bootstrap | NodeOutputExtractor.java | 加 IMAGE_DESCRIPTION 分支 |
| bootstrap | KnowledgeChunkDO.java | 加 3 字段 |
| bootstrap | KnowledgeChunkCreateRequest.java | 加 3 字段 |
| bootstrap | KnowledgeChunkVO.java | 加 3 字段 |
| bootstrap | KnowledgeDocumentServiceImpl.java | embeddingModel 透传 + 映射新字段 |
| bootstrap | KnowledgeChunkServiceImpl.java | 映射新字段 |
| bootstrap | PgRetrieverService.java | JOIN + BM25 适配 |
| bootstrap | KnowledgeChunkMapper.java | BM25 SQL 加字段 |
| 配置 | application.yaml | ai.vision + rag.multimodal |
| 配置 | schema_pg.sql | 加 3 列 |

**新增 15 个文件，修改 20 个文件**

## 依赖顺序

```
阶段一（视觉模型服务 infra-ai）
    │
阶段二（数据模型扩展 bootstrap + framework）
    │
阶段三（Tika 图片提取）
    │
阶段四（图片处理服务 → 依赖阶段一 VisionService）
    │
阶段五（ImageDescriptionNode → 依赖阶段四）
    │
阶段六（embeddingModel 透传）
    │
阶段七（数据库迁移 + 持久化映射）
    │
阶段八（PgVector 检索 JOIN + BM25 适配）
```

阶段一和阶段二可并行。阶段三和阶段四之间有依赖（Tika 产出 ExtractedImage，ImageProcessingService 消费）。

## 验证方案

### 单元测试

- `TikaDocumentParser` 解析含图片 PDF，断言 `ParseResult.images` 非空。
- `ParserNode` 执行后，断言 `context.extractedImages` 非空。
- `VisionClient` 构造的请求体包含 base64 data URI 的 multimodal content 数组。
- `ImageDescriptionNode` 输入图片 context，断言输出 `contentType=IMAGE` 的 chunk。

### 集成测试

- 使用包含图表的 PDF，Pipeline 配置 `parser -> chunker -> image_description -> indexer`。
- 执行分块后断言：
  - `t_knowledge_chunk` 有 `content_type='IMAGE'`。
  - `image_url` 非空。
  - `image_mime_type` 非空或能被默认处理。
  - `t_knowledge_vector` 有对应图片 chunk 向量。
  - `PgRetrieverService` JOIN 返回 `contentType/imageUrl`。

### 回归测试

- 不包含 `image_description` 的 Pipeline 行为不变。
- 普通 Chunk 模式处理同一文件不产生图片 chunk。
- `rag.vector.type=pg` 下功能可用。
- 自定义知识库 embedding 模型在 Pipeline 模式下仍生效。
- 单张图片 VLM 失败时，文本 chunk 仍能正常入库。
- VLM 主模型不可用时，自动降级到备用视觉模型。

## 代码审查优化项

V1 实现经代码审查后，记录以下需要优化的问题，按优先级排列。

> **已确认的已知限制**（不做优化项处理）：
> - `vlmConcurrency` 和 `vlmTimeoutSeconds` 为 V1 预留字段，设计阶段已明确"后续再接入并发和超时控制"。
> - `retrieveByKeyword` 未区分 TEXT/IMAGE 的 BM25 权重，检索结果已携带 `contentType` 字段，调用方可自行处理。
> - 多模态图片提取仅支持 PDF/Word/Excel/PPT 等内嵌图片的二进制格式，不支持 Markdown 外部引用图片。
> - `RetrievedChunk.imageUrl` 为内部对象地址，前端图片代理接口后续实现。
> - `"image/jpg"` 非标准 MIME 类型已确认 Tika 不会产生，已从 `extensionFromMimeType` 移除。
> - `content_type` 列的 B-tree 索引选择性极低（仅 TEXT/IMAGE 两值），已移除。

### Critical

#### C1. 内存压力：全部图片字节同时驻留堆内

**问题：**

图片字节在三个阶段同时驻留堆内：

1. **Tika 解析阶段**：`ImageCollectingExtractor.collectedImages`（`ArrayList<ExtractedImage>`）累积全部图片 `byte[]`。
2. **Pipeline 中间阶段**：`IngestionContext.extractedImages` 持有同一列表引用，穿越 `ChunkerNode` 期间图片数据无事可做，白白占内存。
3. **VLM 调用阶段**：`processSingleImage` 将单张图片 Base64 编码（膨胀 ~33%），此时原始 `byte[]` 仍在 context 中。

**峰值估算：**

| 场景 | 图片数 | 平均大小 | 原始字节 | Base64 峰值 |
|------|--------|----------|----------|-------------|
| 普通文档 | 0-2 | ~200KB | <1MB | ~1.3MB |
| 含图表 PDF | 5-10 | ~500KB | ~5MB | ~6.5MB |
| 图片密集文档 | 20-30 | ~1MB | ~30MB | ~40MB |
| 极端情况 | 50 | 4MB | ~200MB | ~265MB |

极端情况的 ~200MB 是理论上限（受 `maxImagesPerDocument=50` 和 `maxImageBytes=4MB` 约束）。真实场景大多落在 5-30MB 范围。但在**并发摄取**场景下（如同时处理 5 个图片密集文档），堆内存可达 150-1000MB，对默认 JVM 堆配置构成压力。

**涉及文件：** `TikaDocumentParser.java`（`ImageCollectingExtractor`）、`IngestionContext.java`、`ImageProcessingService.java`

**优化方案（V2）：**

方案 A — **临时文件中转**（推荐）：

```java
// ExtractedImage 改为持有 Path
public record ExtractedImage(int index, Path tempFile, String mimeType) {
    public byte[] readData() throws IOException {
        return Files.readAllBytes(tempFile);
    }
    public void cleanup() throws IOException {
        Files.deleteIfExists(tempFile);
    }
}
```

`ImageCollectingExtractor` 写临时文件而非持有 `byte[]`，`ImageProcessingService` 逐张读取处理后立即删除。峰值内存从 N 张降到 1 张。需引入临时文件清理机制（try-finally 或 `deleteOnExit`），防止 pipeline 异常时残留。

方案 B — **提取阶段直接上传**：

`ImageCollectingExtractor` 在提取时立即上传到 RustFS，`ExtractedImage` 只保存 URL。但当前 `ImageCollectingExtractor` 是 static 内部类，无法注入 `FileStorageService`；且设计文档要求 VLM 用 base64 data URI，意味着后续还需从对象存储下载回来，增加网络 RTT。

**V1 策略：** 在 ingestion 入口加并发信号量限制同时执行的 Pipeline 数量（复用 `vlmConcurrency` 配置值），防止单点 OOM。单文档 5-30MB 的真实峰值在有限并发下可控。

### Important

#### I1. `PgRetrieverService` 向量检索新增 JOIN 的性能风险

**问题：**

原查询为单表 HNSW 近邻扫描：

```sql
SELECT id, content, 1 - (embedding <=> ?::vector) AS score
FROM t_knowledge_vector
WHERE metadata->>'collection_name' = ?
ORDER BY embedding <=> ?::vector LIMIT ?
```

现改为 JOIN `t_knowledge_chunk`：

```sql
SELECT v.id, v.content, c.content_type, c.image_url, c.image_mime_type, ...
FROM t_knowledge_vector v
JOIN t_knowledge_chunk c ON c.id = v.id
WHERE v.metadata->>'collection_name' = ?
  AND c.enabled = 1 AND c.deleted = 0
ORDER BY v.embedding <=> ?::vector LIMIT ?
```

这是 **RAG 聊天的热路径**——每次用户提问都会执行。JOIN 带来的影响：

- HNSW 索引扫描返回 topK 个向量 ID 后，还需对每个 ID 做 PK 查找 `t_knowledge_chunk`。由于 `c.id` 是主键，走 Index Nested Loop，单次查找很快（微秒级），但 topK=10 时额外 10 次 PK 查找。
- 新增的 `c.enabled = 1 AND c.deleted = 0` 过滤条件在 JOIN 之后应用，理论上可能过滤掉部分 HNSW 结果，导致返回条目少于 topK（不过当前设计中向量表和 chunk 表是 1:1 关系，且 chunk 被软删除时向量也应同步删除，所以实际影响小）。
- 传输 3 个额外字段（`content_type`、`image_url`、`image_mime_type`）的网络开销可忽略。

**文件：** `bootstrap/.../rag/core/retrieve/PgRetrieverService.java:60-77`

**优化方案：**

1. **验证**：在 chunk 量级 >100 万的生产数据上跑 `EXPLAIN ANALYZE`，确认走 PK 索引且耗时在可接受范围（<5ms）。
2. **备选方案**：如果 JOIN 确实成为瓶颈，可将多模态字段冗余到 `t_knowledge_vector.metadata` JSONB 列：

```sql
-- 写入时把多模态字段塞进 metadata
UPDATE t_knowledge_vector SET metadata = jsonb_set(
    COALESCE(metadata, '{}'),
    '{multimodal}',
    jsonb_build_object('contentType', ?, 'imageUrl', ?, 'imageMimeType', ?)
);

-- 查询时从 metadata 直接提取，无需 JOIN
SELECT id, content,
    metadata->>'contentType' AS content_type,
    metadata->>'imageUrl' AS image_url,
    metadata->>'imageMimeType' AS image_mime_type,
    1 - (embedding <=> ?::vector) AS score
FROM t_knowledge_vector
WHERE metadata->>'collection_name' = ?
ORDER BY embedding <=> ?::vector LIMIT ?
```

缺点：数据冗余，chunk 表和向量表的元数据需要保持同步。

#### I2. `batchCreate` 方法未映射 `parentId`

**问题：**

`KnowledgeChunkServiceImpl` 有两个写入方法：

```java
// 单条创建 — 正确映射了 parentId
public String create(KnowledgeChunkCreateRequest requestParam) {
    return KnowledgeChunkDO.builder()
            .parentId(requestParam.getParentId())  // ✅ 有
            .build();
}

// 批量创建 — 漏掉了 parentId
private KnowledgeChunkDO buildChunkDO(KnowledgeChunkCreateRequest request, String username) {
    return KnowledgeChunkDO.builder()
            // ❌ 缺少 .parentId(request.getParentId())
            .build();
}
```

`batchCreate` 是 Pipeline 模式的写入路径（`persistChunksAndVectorsAtomically` → 批量调用），意味着 **Pipeline 模式下所有 chunk 的 `parentId` 都是 null**。当前 Pipeline 未启用父子分块模式，影响有限，但如果后续在 Pipeline 中启用父子分块，所有 chunk 都会变成孤立节点。

**文件：** `bootstrap/.../knowledge/service/impl/KnowledgeChunkServiceImpl.java:204-221`

**优化方案：** 在 `batchCreate` 的 builder 中补充 `.parentId(request.getParentId())`。
