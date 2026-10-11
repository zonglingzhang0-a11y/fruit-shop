package com.fruit.shop.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 效果看板的按日趋势点（主字段：bizDate/strategy/scrapRate/grossProfit/revenue）。 */
public record EffectDailyRow(
        LocalDate bizDate,
        String strategy,
        BigDecimal scrapRate,
        BigDecimal grossProfit,
        BigDecimal revenue,
        BigDecimal soldQty,
        BigDecimal scrapQty,
        BigDecimal cost,
        BigDecimal stockoutRate) {
}
