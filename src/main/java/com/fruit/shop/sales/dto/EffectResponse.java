package com.fruit.shop.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 效果看板响应（docs/01-技术方案.md 第 5 节接口 11）。
 *
 * <p>主字段：{@code scrapRate / grossProfit / revenue / cost / soldQty / scrapQty / stockoutRate / daily}。
 * 数据来源优先 {@code strategy_daily_stat}（按 strategy 聚合）；该表在区间内没数据时，
 * 回退为 {@code sales_record} 实时汇总，并在 {@code source / note} 里如实说明（不伪造策略归属）。
 */
public record EffectResponse(
        BigDecimal scrapRate,
        BigDecimal grossProfit,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal soldQty,
        BigDecimal scrapQty,
        BigDecimal stockoutRate,
        List<EffectDailyRow> daily,
        LocalDate from,
        LocalDate to,
        String strategy,
        String activeStrategy,
        String source,
        boolean costComplete,
        String note,
        List<StrategySummary> byStrategy) {
}
