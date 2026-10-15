package com.fruit.shop.pricing.strategy;

import java.math.BigDecimal;

/**
 * 定价建议提案。
 *
 * @param discountRate 折扣系数，必须落在候选档 {1.00,0.90,0.80,0.70,0.60,0.50} 内且 &lt;= 1.00
 * @param expectedQty  期望销量（可为 null，表示该策略不给估计）
 * @param reason       给店主看的人话理由（会写入 pricing_suggestion.reason）
 * @param effectiveStrategy 实际给出这个价格的策略（null = 就是被请求的那个策略）；
 *                          MODEL 回退规则版时填 RULE，写入 pricing_suggestion.effective_strategy
 */
public record PricingProposal(BigDecimal discountRate, BigDecimal expectedQty, String reason, String effectiveStrategy) {

    public PricingProposal(BigDecimal discountRate, BigDecimal expectedQty, String reason) {
        this(discountRate, expectedQty, reason, null);
    }
}
