-- =============================================================================
-- 已有库的增量结构变更（只在 app.db.keep-existing=true 且跳过了 schema-h2.sql 时执行）
--
-- 规则：每条语句都必须可重复执行（IF NOT EXISTS），因为每次启动都会跑一遍；
--       schema-h2.sql 里加了新列/新索引，就在这里补一条对应的增量语句，
--       否则保留下来的旧文件库缺列，启动后写入会报错。
-- =============================================================================

-- #3 销售回流幂等：业务流水号
ALTER TABLE sales_record ADD COLUMN IF NOT EXISTS request_no VARCHAR(64) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_request_no ON sales_record (request_no);
