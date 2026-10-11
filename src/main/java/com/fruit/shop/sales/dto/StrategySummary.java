package com.fruit.shop.sales.dto;

import java.math.BigDecimal;

/** 按策略汇总（FIXED / RULE / MODEL 对比是"效果看板"的核心，策略之间必须能横向比较）。 */
public record StrategySummary(
        String strategy,
        BigDecimal soldQty,
        BigDecimal scrapQty,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal grossProfit,
        BigDecimal scrapRate,
        BigDecimal stockoutRate,
        int days) {
}
