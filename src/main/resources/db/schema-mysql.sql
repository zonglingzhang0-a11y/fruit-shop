-- =============================================================================
-- 街边水果店临期果品动态定价与到店客流适配推送系统 —— MySQL 8 建表脚本（答辩/部署口径）
-- 与 db/schema-h2.sql 字段、索引、语义完全一致；差异仅在 BOOLEAN -> TINYINT(1)、TIMESTAMP -> DATETIME（原因见下）。
--
-- v2 变更（对照参考架构《水果店临期定价系统设计》，差异分析见 docs/07）：
--   A. fruit_batch 临期判定指标：freshness / estimated_sellable_days / planned_sale_qty
--      / expected_scrap_qty / at_risk
--   B. 定价约束留痕：pricing_suggestion.price_floor_hit / raw_discount_rate / adjust_seq、
--      pricing_decision.reject_reason
--   C. 推送拆分：push_task（计划）+ push_log（发送与效果归因）
--   D. 新增 shop_traffic_profile（星期 × 小时客流规律）
--
-- 执行方式：
--   1. 随应用自动执行（推荐）：--spring.profiles.active=mysql，库由连接串决定（见 application-mysql.yml）；
--   2. 手工执行：先建库再导入，例如
--        mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS fruit_shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
--        mysql -uroot -p fruit_shop < schema-mysql.sql
--      （Windows PowerShell 不支持 < 重定向，用：mysql -uroot -p fruit_shop -e "source src/main/resources/db/schema-mysql.sql"）
--   需要 MySQL 8.0.19 及以上（8.0.17/8.0.18 把 member 当成保留字）。
--   脚本里不写 CREATE DATABASE / USE：随应用执行时它会把连接池里的连接切到别的库，表建错地方。
--   时间列一律用 DATETIME 而不是 TIMESTAMP：MySQL 的 TIMESTAMP 只到 2038-01-19（2038 年问题），
--   而且会按会话时区换算；DATETIME 到 9999 年、按原样存取，与 Java 的 LocalDateTime 语义一致。
-- =============================================================================

-- 先删"建表完成"标志表（seed_load_log，见 com.fruit.shop.config.SchemaInitConfig.MARKER_TABLE）：
-- 本脚本是逐条 DROP/CREATE、不在一个事务里，中途被打断（Ctrl-C、权限不足、锁等待超时）时，
-- 标志表必须已经不在，下次以 keep-existing 启动才会重新完整建表，而不是把半套表当成完整结构。
-- 它在脚本末尾重新创建，是最后一张表。
DROP TABLE IF EXISTS seed_load_log;

DROP TABLE IF EXISTS health_check;
CREATE TABLE health_check (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  check_name  VARCHAR(64)  NOT NULL,
  status      VARCHAR(16)  NOT NULL,
  checked_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS fruit_item;
CREATE TABLE fruit_item (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  item_code       VARCHAR(32)   NOT NULL,
  item_name       VARCHAR(64)   NOT NULL,
  category_name   VARCHAR(32)   NOT NULL,
  unit            VARCHAR(16)   NOT NULL,
  cost_price      DECIMAL(10,2) NOT NULL,
  base_price      DECIMAL(10,2) NOT NULL,
  shelf_life_days INT           NOT NULL,
  loss_rate_pct   DECIMAL(5,2)  NOT NULL DEFAULT 0,
  status          VARCHAR(16)   NOT NULL DEFAULT 'ON',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fruit_item_code (item_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS fruit_batch;
CREATE TABLE fruit_batch (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  item_id      BIGINT        NOT NULL,
  batch_no     VARCHAR(32)   NOT NULL,
  arrive_date  DATE          NOT NULL,
  expire_date  DATE          NOT NULL,
  init_qty     DECIMAL(10,2) NOT NULL,
  remain_qty   DECIMAL(10,2) NOT NULL,
  cost_price   DECIMAL(10,2) NOT NULL,
  scrap_qty    DECIMAL(10,2) NOT NULL DEFAULT 0,
  status       VARCHAR(16)   NOT NULL DEFAULT 'ON_SALE',
  freshness               DECIMAL(6,4)  NULL,
  estimated_sellable_days DECIMAL(8,3)  NULL,
  planned_sale_qty        DECIMAL(10,2) NULL,
  expected_scrap_qty      DECIMAL(10,2) NULL,
  at_risk                 TINYINT(1)    NULL,
  metrics_updated_at      DATETIME     NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_no (batch_no),
  KEY idx_batch_item_expire (item_id, expire_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS pricing_suggestion;
CREATE TABLE pricing_suggestion (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
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
  price_floor_hit   TINYINT(1)    NOT NULL DEFAULT 0,
  raw_discount_rate DECIMAL(4,2)  NULL,
  adjust_seq        INT           NOT NULL DEFAULT 1,
  freshness               DECIMAL(6,4)  NULL,
  estimated_sellable_days DECIMAL(8,3)  NULL,
  expected_scrap_qty      DECIMAL(10,2) NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_sugg_date_strategy (biz_date, strategy)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS pricing_decision;
CREATE TABLE pricing_decision (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  suggestion_id   BIGINT        NOT NULL,
  suggested_price DECIMAL(10,2) NOT NULL,
  final_price     DECIMAL(10,2) NOT NULL,
  accepted        TINYINT(1)    NOT NULL,
  operator        VARCHAR(32)   NOT NULL,
  reason          VARCHAR(255)  NULL,
  reject_reason   VARCHAR(64)   NULL,
  decided_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_decision_suggestion (suggestion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS member;
CREATE TABLE member (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  member_no    VARCHAR(32) NOT NULL,
  nickname     VARCHAR(32) NOT NULL,
  phone_tail   VARCHAR(8)  NULL,
  joined_date  DATE        NOT NULL,
  push_enabled TINYINT(1)  NOT NULL DEFAULT 1,
  created_at   DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_no (member_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS member_pref;
CREATE TABLE member_pref (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  member_id     BIGINT       NOT NULL,
  category_name VARCHAR(32)  NOT NULL,
  buy_count     INT          NOT NULL DEFAULT 0,
  last_buy_date DATE         NULL,
  score         DECIMAL(6,3) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_pref (member_id, category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS shop_traffic_hourly;
CREATE TABLE shop_traffic_hourly (
  id             BIGINT     NOT NULL AUTO_INCREMENT,
  stat_date      DATE       NOT NULL,
  hour_of_day    INT        NOT NULL,
  order_count    INT        NOT NULL,
  customer_count INT        NOT NULL,
  is_peak        TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_traffic_date_hour (stat_date, hour_of_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS shop_traffic_profile;
CREATE TABLE shop_traffic_profile (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  weekday            INT           NOT NULL,
  hour_of_day        INT           NOT NULL,
  avg_order_count    DECIMAL(10,3) NOT NULL,
  avg_customer_count DECIMAL(10,3) NULL,
  sample_days        INT           NOT NULL,
  is_peak            TINYINT(1)    NOT NULL DEFAULT 0,
  computed_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_profile_weekday_hour (weekday, hour_of_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS push_task;
CREATE TABLE push_task (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  biz_date              DATE          NOT NULL,
  related_item_id       BIGINT        NULL,
  related_batch_id      BIGINT        NULL,
  related_suggestion_id BIGINT        NULL,
  channel               VARCHAR(16)   NOT NULL DEFAULT 'IN_APP',
  plan_time             DATETIME     NOT NULL,
  reason                VARCHAR(128)  NULL,
  audience_rule         VARCHAR(128)  NULL,
  target_count          INT           NOT NULL DEFAULT 0,
  title                 VARCHAR(64)   NOT NULL,
  content               VARCHAR(512)  NOT NULL,
  status                VARCHAR(16)   NOT NULL DEFAULT 'PLANNED',
  created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at               DATETIME     NULL,
  discount_rate         DECIMAL(4,2)  NULL COMMENT '预览时店主看到的折扣（无确认价时发送沿用它）',
  PRIMARY KEY (id),
  KEY idx_push_task_date (biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS push_log;
CREATE TABLE push_log (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  task_id              BIGINT      NOT NULL,
  member_id            BIGINT      NULL,
  sent_at              DATETIME   NOT NULL,
  opened               TINYINT(1)  NOT NULL DEFAULT 0,
  opened_at            DATETIME   NULL,
  converted            TINYINT(1)  NOT NULL DEFAULT 0,
  converted_at         DATETIME   NULL,
  related_sale_id      BIGINT      NULL,
  convert_window_hours INT         NULL,
  skip_reason          VARCHAR(32) NULL,
  PRIMARY KEY (id),
  KEY idx_push_log_task (task_id),
  KEY idx_push_log_member (member_id, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS sales_record;
CREATE TABLE sales_record (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  biz_date    DATE          NOT NULL,
  sale_time   DATETIME     NOT NULL,
  item_id     BIGINT        NOT NULL,
  batch_id    BIGINT        NULL,
  quantity    DECIMAL(10,2) NOT NULL,
  unit_price  DECIMAL(10,2) NOT NULL,
  is_discount TINYINT(1)    NOT NULL DEFAULT 0,
  is_return   TINYINT(1)    NOT NULL DEFAULT 0,
  member_id   BIGINT        NULL,
  source      VARCHAR(16)   NOT NULL DEFAULT 'REAL',
  -- 幂等键必须区分大小写：MySQL 默认排序规则（8.0 为 utf8mb4_0900_ai_ci）不区分大小写，
  -- 'aB3x' 和 'Ab3X' 会被当成同一个流水号，第二笔真实销售会被当成重复吞掉；与 H2 一致改用二进制比较
  request_no  VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '业务流水号（幂等键）',
  requested_qty DECIMAL(10,2) NULL COMMENT '原始请求数量（带符号；库存不足时 quantity 会被截断）',
  PRIMARY KEY (id),
  KEY idx_sales_date_item (biz_date, item_id),
  UNIQUE KEY uk_sales_request_no (request_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS strategy_daily_stat;
CREATE TABLE strategy_daily_stat (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  biz_date      DATE          NOT NULL,
  strategy      VARCHAR(16)   NOT NULL,
  sold_qty      DECIMAL(12,2) NOT NULL,
  scrap_qty     DECIMAL(12,2) NOT NULL,
  revenue       DECIMAL(12,2) NOT NULL,
  cost          DECIMAL(12,2) NOT NULL,
  gross_profit  DECIMAL(12,2) NOT NULL,
  scrap_rate    DECIMAL(6,4)  NOT NULL,
  stockout_rate DECIMAL(6,4)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stat_date_strategy (biz_date, strategy)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS seed_load_log;
CREATE TABLE seed_load_log (
  id        BIGINT      NOT NULL AUTO_INCREMENT,
  seed_name VARCHAR(64) NOT NULL,
  row_count INT         NOT NULL,
  loaded_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO health_check (check_name, status) VALUES ('bootstrap', 'OK');
