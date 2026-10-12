package com.fruit.shop.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 策略日统计 Mapper。 */
public interface StrategyDailyStatMapper extends BaseMapper<StrategyDailyStat> {

    /**
     * 原子累加一行（读—改—写交给数据库，避免并发回流丢统计）。
     * 派生列（gross_profit、scrap_rate）写在前面：MySQL 按从左到右求值、会读到已赋的新值，
     * H2 统一读旧值；派生列先算、全部基于旧值显式加增量，两种库结果一致。
     *
     * @return 受影响行数；0 表示当天该策略还没有统计行
     */
    @Update("UPDATE strategy_daily_stat SET "
            + "gross_profit = (revenue + #{revenue}) - (cost + #{cost}), "
            + "scrap_rate = CASE WHEN scrap_qty + sold_qty + #{soldQty} <= 0 THEN 0 "
            + "  ELSE ROUND(scrap_qty / (scrap_qty + sold_qty + #{soldQty}), 4) END, "
            + "sold_qty = sold_qty + #{soldQty}, "
            + "revenue = revenue + #{revenue}, "
            + "cost = cost + #{cost} "
            + "WHERE biz_date = #{bizDate} AND strategy = #{strategy}")
    int accumulate(@Param("bizDate") LocalDate bizDate,
                   @Param("strategy") String strategy,
                   @Param("soldQty") BigDecimal soldQty,
                   @Param("revenue") BigDecimal revenue,
                   @Param("cost") BigDecimal cost);
}
