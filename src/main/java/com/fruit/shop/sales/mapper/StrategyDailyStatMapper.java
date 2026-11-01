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

    /**
     * 报废人工登记（FR-06）：原子累加当日报废量并重算损耗率，口径与 {@link #upsertAccumulate} 相同
     * （损耗率 = 报废量 ÷（销量 + 报废量）；派生列写在前面、基于旧值显式加增量）。
     * cost 只记销售成本（与种子统计口径一致：gross_profit = revenue − cost，不含报废成本），所以报废不动 cost。
     *
     * <p>参数显式 CAST 成 DECIMAL(10,2)：分子是"列 + 参数"时，H2 推断不出参数类型，除法会按整数算成 0
     * （分子是纯列时没问题，所以 {@link #upsertAccumulate} 不需要）。MySQL 8 同样支持这个写法。
     */
    @Insert("INSERT INTO strategy_daily_stat "
            + "(biz_date, strategy, sold_qty, scrap_qty, revenue, cost, gross_profit, scrap_rate, stockout_rate) "
            + "VALUES (#{bizDate}, #{strategy}, 0, CAST(#{scrapQty} AS DECIMAL(10,2)), 0, 0, 0, 1, 0) "
            + "ON DUPLICATE KEY UPDATE "
            + "scrap_rate = CASE WHEN scrap_qty + CAST(#{scrapQty} AS DECIMAL(10,2)) + sold_qty <= 0 THEN 0 "
            + "  ELSE ROUND((scrap_qty + CAST(#{scrapQty} AS DECIMAL(10,2)))"
            + " / (scrap_qty + CAST(#{scrapQty} AS DECIMAL(10,2)) + sold_qty), 4) END, "
            + "scrap_qty = scrap_qty + CAST(#{scrapQty} AS DECIMAL(10,2))")
    int upsertScrap(@Param("bizDate") LocalDate bizDate,
                    @Param("strategy") String strategy,
                    @Param("scrapQty") BigDecimal scrapQty);
}
