package com.fruit.shop.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 策略日统计 Mapper。 */
public interface StrategyDailyStatMapper extends BaseMapper<StrategyDailyStat> {

    /**
     * 原子"插入或累加"一行（唯一索引 uk_stat_date_strategy 冲突时走 UPDATE 分支），并发回流不丢统计、也不会死锁。
     *
     * <p>UPDATE 分支里派生列（gross_profit、scrap_rate）写在前面、全部基于旧值显式加增量：
     * MySQL 按从左到右求值、后面的赋值会读到前面已改的新值，H2 统一读旧值；派生列先算，两种库结果一致。
     * 增量用参数而不是 {@code VALUES(col)}（MySQL 8.0.20 起已标记弃用）。
     */
    @Insert("INSERT INTO strategy_daily_stat "
            + "(biz_date, strategy, sold_qty, scrap_qty, revenue, cost, gross_profit, scrap_rate, stockout_rate) "
            + "VALUES (#{bizDate}, #{strategy}, #{soldQty}, 0, #{revenue}, #{cost}, #{revenue} - #{cost}, 0, 0) "
            + "ON DUPLICATE KEY UPDATE "
            + "gross_profit = (revenue + #{revenue}) - (cost + #{cost}), "
            + "scrap_rate = CASE WHEN scrap_qty + sold_qty + #{soldQty} <= 0 THEN 0 "
            + "  ELSE ROUND(scrap_qty / (scrap_qty + sold_qty + #{soldQty}), 4) END, "
            + "sold_qty = sold_qty + #{soldQty}, "
            + "revenue = revenue + #{revenue}, "
            + "cost = cost + #{cost}")
    int upsertAccumulate(@Param("bizDate") LocalDate bizDate,
                         @Param("strategy") String strategy,
                         @Param("soldQty") BigDecimal soldQty,
                         @Param("revenue") BigDecimal revenue,
                         @Param("cost") BigDecimal cost);
}
