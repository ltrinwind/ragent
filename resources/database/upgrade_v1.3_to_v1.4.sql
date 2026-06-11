-- ============================================================
-- 多模态文档嵌入支持 - 数据库升级脚本
-- 从 v1.3 升级到 v1.4
-- ============================================================

-- t_knowledge_chunk 增加多模态字段
ALTER TABLE t_knowledge_chunk
  ADD COLUMN IF NOT EXISTS content_type VARCHAR(20) NOT NULL DEFAULT 'TEXT';

ALTER TABLE t_knowledge_chunk
  ADD COLUMN IF NOT EXISTS image_url TEXT;

ALTER TABLE t_knowledge_chunk
  ADD COLUMN IF NOT EXISTS image_mime_type VARCHAR(100);

-- 注释
COMMENT ON COLUMN t_knowledge_chunk.content_type IS '内容类型：TEXT/IMAGE';
COMMENT ON COLUMN t_knowledge_chunk.image_url IS '图片对象存储地址';
COMMENT ON COLUMN t_knowledge_chunk.image_mime_type IS '图片 MIME 类型';
