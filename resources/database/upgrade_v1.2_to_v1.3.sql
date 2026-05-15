-- ragent v1.2 -> v1.3 升级脚本
-- t_message 表：新增检索 chunk 内容

ALTER TABLE t_message ADD COLUMN contexts TEXT;
