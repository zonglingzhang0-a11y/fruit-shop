-- =============================================================================
-- 街边水果店临期果品动态定价与到店客流适配推送系统 —— H2 建表脚本（开发/演示口径）
-- 与 db/schema-mysql.sql 字段完全一致；H2 以 MySQL 兼容模式运行。
-- 约定：金额 DECIMAL(10,2)，日期 DATE，时间 TIMESTAMP，布尔用 BOOLEAN。
--
-- v2 变更（对照参考架构《水果店临期定价系统设计》，差异分析见 docs/07）：
--   A. fruit_batch 增加"临期判定指标"：freshness / estimated_sellable_days / planned_sale_qty
--      / expected_scrap_qty / at_risk —— 对应参考表《临期判定》的公式链
--   B. 定价约束留痕：pricing_suggestion.price_floor_hit / raw_discount_rate / adjust_seq，
--      pricing_decision.reject_reason —— 对应参考表《定价引擎》第 4 步"最低折扣线 + 每日调价上限"
--   C. 推送拆成 push_task（计划）+ push_log（发送与效果归因）—— 对应参考表 push_task/push_log
--   D. 新增 shop_traffic_profile：按"星期 × 小时"的客流规律 —— 对应参考表 traffic_profile
-- =============================================================================

-- 先删"建表完成"标志表（seed_load_log，见 com.fruit.shop.config.SchemaInitConfig.MARKER_TABLE）：
-- 本脚本是逐条 DROP/CREATE、不在一个事务里，中途被打断（Ctrl-C、权限不足、锁等待超时）时，
-- 标志表必须已经不在，下次以 keep-existing 启动才会重新完整建表，而不是把半套表当成完整结构。
-- 它在脚本末尾重新创建，是最后一张表。
DROP TABLE IF EXISTS seed_load_log;

-- 0. 自检表 -------------------------------------------------------------------
DROP TABLE IF EXISTS health_check;
CREATE TABLE health_check (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  check_name  VARCHAR(64)  NOT NULL,
  status      VARCHAR(16)  NOT NULL,
  checked_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 1. 单品 ---------------------------------------------------------------------
DROP TABLE IF EXISTS fruit_item;
CREATE TABLE fruit_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_code       VARCHAR(32)   NOT NULL,
  item_name       VARCHAR(64)   NOT NULL,
  category_name   VARCHAR(32)   NOT NULL,
  unit            VARCHAR(16)   NOT NULL,
  cost_price      DECIMAL(10,2) NOT NULL,
  base_price      DECIMAL(10,2) NOT NULL,
  shelf_life_days INT           NOT NULL,
  loss_rate_pct   DECIMAL(5,2)  NOT NULL DEFAULT 0,
  status          VARCHAR(16)   NOT NULL DEFAULT 'ON',
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_fruit_item_code ON fruit_item (item_code);

-- 2. 到货批次（临期台账核心 + 临期判定指标）-----------------------------------
DROP TABLE IF EXISTS fruit_batch;
CREATE TABLE fruit_batch (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id      BIGINT        NOT NULL,
  batch_no     VARCHAR(32)   NOT NULL,
  arrive_date  DATE          NOT NULL,
  expire_date  DATE          NOT NULL,
  init_qty     DECIMAL(10,2) NOT NULL,
  remain_qty   DECIMAL(10,2) NOT NULL,
  cost_price   DECIMAL(10,2) NOT NULL,
  scrap_qty    DECIMAL(10,2) NOT NULL DEFAULT 0,
  status       VARCHAR(16)   NOT NULL DEFAULT 'ON_SALE',
  -- v2 临期判定指标（公式与参考架构《临期判定》一致，口径见 docs/07 第 2 节）
  freshness               DECIMAL(6,4)  NULL,  -- 剩余天数 ÷ 标准保质期（0~1）
  estimated_sellable_days DECIMAL(8,3)  NULL,  -- 剩余库存 ÷ 近 7 天日均销量
  planned_sale_qty        DECIMAL(10,2) NULL,  -- min(剩余库存, 日均销量 × 剩余天数)
  expected_scrap_qty      DECIMAL(10,2) NULL,  -- 剩余库存 − 预计销量
  at_risk                 BOOLEAN       NULL,  -- 预计可售天数 > 剩余天数
  metrics_updated_at      TIMESTAMP     NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_batch_item_expire ON fruit_batch (item_id, expire_date);
CREATE UNIQUE INDEX uk_batch_no ON fruit_batch (batch_no);

-- 3. 定价建议 -----------------------------------------------------------------
DROP TABLE IF EXISTS pricing_suggestion;
CREATE TABLE pricing_suggestion (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_date        DATE          NOT NULL,
  item_id         BIGINT        NOT NULL,
  batch_id        BIGINT        NOT NULL,
  days_left       INT           NOT NULL,
  remain_qty      DECIMAL(10,2) NOT NULL,
  original_price  DECIMAL(10,2) NOT NULL,
  suggested_price DECIMAL(10,2) NOT NULL,
  discount_rate   DECIMAL(4,2)  NOT NULL,
  strategy        VARCHAR(16)   NOT NULL,
  expected_qty    DECIMAL(10,2) NULL,
  reason          VARCHAR(255)  NULL,
  -- v2 定价约束留痕
  price_floor_hit   BOOLEAN      NOT NULL DEFAULT FALSE, -- 被最低折扣线拦住
  raw_discount_rate DECIMAL(4,2) NULL,                   -- 约束前的原始档位
  adjust_seq        INT          NOT NULL DEFAULT 1,     -- 当天该批次第几次调价
  -- 临期判定指标快照（事后可复核"为什么这天建议打折"）
  freshness               DECIMAL(6,4)  NULL,
  estimated_sellable_days DECIMAL(8,3)  NULL,
  expected_scrap_qty      DECIMAL(10,2) NULL,
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_sugg_date_strategy ON pricing_suggestion (biz_date, strategy);

-- 4. 定价决策（店主确认/改价，人机协同证据）------------------------------------
DROP TABLE IF EXISTS pricing_decision;
CREATE TABLE pricing_decision (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  suggestion_id   BIGINT        NOT NULL,
  suggested_price DECIMAL(10,2) NOT NULL,
  final_price     DECIMAL(10,2) NOT NULL,
  accepted        BOOLEAN       NOT NULL,
  operator        VARCHAR(32)   NOT NULL,
  reason          VARCHAR(255)  NULL,
  reject_reason   VARCHAR(64)   NULL,   -- v2：不接受时记原因
  decided_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_decision_suggestion ON pricing_decision (suggestion_id);

-- 5. 会员与品类偏好 ------------------------------------------------------------
DROP TABLE IF EXISTS member;
CREATE TABLE member (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_no    VARCHAR(32) NOT NULL,
  nickname     VARCHAR(32) NOT NULL,
  phone_tail   VARCHAR(8)  NULL,
  joined_date  DATE        NOT NULL,
  push_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_member_no ON member (member_no);

DROP TABLE IF EXISTS member_pref;
CREATE TABLE member_pref (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id     BIGINT       NOT NULL,
  category_name VARCHAR(32)  NOT NULL,
  buy_count     INT          NOT NULL DEFAULT 0,
  last_buy_date DATE         NULL,
  score         DECIMAL(6,3) NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_member_pref ON member_pref (member_id, category_name);

-- 6. 时段客流（用历史成交笔数代替真实人流）-------------------------------------
DROP TABLE IF EXISTS shop_traffic_hourly;
CREATE TABLE shop_traffic_hourly (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  stat_date      DATE    NOT NULL,
  hour_of_day    INT     NOT NULL,
  order_count    INT     NOT NULL,
  customer_count INT     NOT NULL,
  is_peak        BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_traffic_date_hour ON shop_traffic_hourly (stat_date, hour_of_day);

-- 6b. 周内客流规律（v2，参考架构 traffic_profile：星期 × 小时，每晚重算）--------
DROP TABLE IF EXISTS shop_traffic_profile;
CREATE TABLE shop_traffic_profile (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  weekday            INT           NOT NULL,  -- 1=周一 … 7=周日
  hour_of_day        INT           NOT NULL,  -- 0~23
  avg_order_count    DECIMAL(10,3) NOT NULL,  -- 按样本天数平均（不是按日历天数，避免闭店小时拉低）
  avg_customer_count DECIMAL(10,3) NULL,
  sample_days        INT           NOT NULL,
  is_peak            BOOLEAN       NOT NULL DEFAULT FALSE,
  computed_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_profile_weekday_hour ON shop_traffic_profile (weekday, hour_of_day);

-- 7a. 推送任务（v2，参考架构 push_task：计划）-----------------------------------
DROP TABLE IF EXISTS push_task;
CREATE TABLE push_task (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_date              DATE         NOT NULL,
  related_item_id       BIGINT       NULL,
  related_batch_id      BIGINT       NULL,
  related_suggestion_id BIGINT       NULL,
  channel               VARCHAR(16)  NOT NULL DEFAULT 'IN_APP',
  plan_time             TIMESTAMP    NOT NULL,          -- 计划发送时间（高峰前 30~60 分钟）
  reason                VARCHAR(128) NULL,              -- 为什么选这个时间/人群
  audience_rule         VARCHAR(128) NULL,
  target_count          INT          NOT NULL DEFAULT 0,
  title                 VARCHAR(64)  NOT NULL,
  content               VARCHAR(512) NOT NULL,
  status                VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',  -- PLANNED/SENT/CANCELLED
  created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at               TIMESTAMP    NULL,
  discount_rate         DECIMAL(4,2) NULL               -- 预览时店主看到的折扣（无确认价时发送沿用它）
);
CREATE INDEX idx_push_task_date ON push_task (biz_date);

-- 7b. 推送日志（v2，参考架构 push_log：发送与效果归因）-------------------------
DROP TABLE IF EXISTS push_log;
CREATE TABLE push_log (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id              BIGINT      NOT NULL,
  member_id            BIGINT      NULL,   -- NULL = 群发文案
  sent_at              TIMESTAMP   NOT NULL,
  opened               BOOLEAN     NOT NULL DEFAULT FALSE,
  opened_at            TIMESTAMP   NULL,
  converted            BOOLEAN     NOT NULL DEFAULT FALSE,
  converted_at         TIMESTAMP   NULL,
  related_sale_id      BIGINT      NULL,   -- 归因到的销售记录
  convert_window_hours INT         NULL,   -- 归因窗口（默认 3 小时）
  skip_reason          VARCHAR(32) NULL    -- 限频/静默/退订
);
CREATE INDEX idx_push_log_task ON push_log (task_id);
CREATE INDEX idx_push_log_member ON push_log (member_id, sent_at);

-- 8. 销售回流 -----------------------------------------------------------------
DROP TABLE IF EXISTS sales_record;
CREATE TABLE sales_record (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_date    DATE          NOT NULL,
  sale_time   TIMESTAMP     NOT NULL,
  item_id     BIGINT        NOT NULL,
  batch_id    BIGINT        NULL,
  quantity    DECIMAL(10,2) NOT NULL,
  unit_price  DECIMAL(10,2) NOT NULL,
  is_discount BOOLEAN       NOT NULL DEFAULT FALSE,
  is_return   BOOLEAN       NOT NULL DEFAULT FALSE,
  member_id   BIGINT        NULL,   -- v2：归因需要（推送转化统计）
  source      VARCHAR(16)   NOT NULL DEFAULT 'REAL',
  request_no  VARCHAR(64)   NULL,   -- 业务流水号（幂等键）：同一笔销售重复提交只记一次
  requested_qty DECIMAL(10,2) NULL  -- 原始请求数量（带符号；库存不足时 quantity 会被截断）
);
CREATE INDEX idx_sales_date_item ON sales_record (biz_date, item_id);
CREATE UNIQUE INDEX uk_sales_request_no ON sales_record (request_no);

-- 9. 策略日统计（效果看板数据源）-----------------------------------------------
DROP TABLE IF EXISTS strategy_daily_stat;
CREATE TABLE strategy_daily_stat (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_date      DATE          NOT NULL,
  strategy      VARCHAR(16)   NOT NULL,
  sold_qty      DECIMAL(12,2) NOT NULL,
  scrap_qty     DECIMAL(12,2) NOT NULL,
  revenue       DECIMAL(12,2) NOT NULL,
  cost          DECIMAL(12,2) NOT NULL,
  gross_profit  DECIMAL(12,2) NOT NULL,
  scrap_rate    DECIMAL(6,4)  NOT NULL,
  stockout_rate DECIMAL(6,4)  NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_stat_date_strategy ON strategy_daily_stat (biz_date, strategy);

-- 10. 种子数据标记（避免重复导入）--------------------------------------------
DROP TABLE IF EXISTS seed_load_log;
CREATE TABLE seed_load_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  seed_name  VARCHAR(64) NOT NULL,
  row_count  INT         NOT NULL,
  loaded_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 骨架自检数据
INSERT INTO health_check (check_name, status) VALUES ('bootstrap', 'OK');
