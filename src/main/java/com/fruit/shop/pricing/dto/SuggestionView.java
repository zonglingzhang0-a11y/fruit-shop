package com.fruit.shop.pricing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 定价建议视图（docs/01-技术方案.md 第 5 节接口 5）。
 * {@code decided / finalPrice} 由 pricing_decision 联表得到，前端不必再发第二个请求。
 *
 * <p>v2 追加（docs/07 第 1 节 B）：
 * <ul>
 *   <li>{@code priceFloorHit / rawDiscountRate}：是否被最低折扣线拦住、"本可以给到"的原始档位；</li>
 *   <li>{@code adjustSeq}：该批次当天第几次调价（受每日上限约束）；</li>
 *   <li>{@code freshness / estimatedSellableDays / expectedScrapQty}：建议生成时的临期判定指标快照。</li>
 * </ul>
 */
public record SuggestionView(
        Long id,
        LocalDate bizDate,
        Long itemId,
        String itemName,
        Long batchId,
        String batchNo,
        Integer daysLeft,
        BigDecimal remainQty,
        BigDecimal originalPrice,
        BigDecimal suggestedPrice,
        BigDecimal discountRate,
        String strategy,
        BigDecimal expectedQty,
        String reason,
        boolean decided,
        BigDecimal finalPrice,
        Boolean priceFloorHit,
        BigDecimal rawDiscountRate,
        Integer adjustSeq,
        BigDecimal freshness,
        BigDecimal estimatedSellableDays,
        BigDecimal expectedScrapQty) {
}
