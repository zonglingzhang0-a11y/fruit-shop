package com.fruit.shop.pricing.strategy;

import com.fruit.shop.pricing.PricingPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * FIXED：固定价基线——一律原价，不打折（代表"店主不降价"的现状）。
 *
 * <p>与离线评测 {@code scripts/evaluate_strategies.py::make_fixed_pricing_fn} 同口径
 * （两边都用 {@code scripts/pricing_rule.py::FIXED_RATE}，契约测试 {@code PricingRuleContractTest} 锁定）。
 * 刻意不看剩余天数、不看存量：它是 RULE/MODEL 的效果对照组，评测报告里的 FIXED 数字就是它。
 */
@Component
public class FixedPricePricingStrategy implements PricingStrategy {

    /** 与 pricing_rule.FIXED_RATE 一致：永远原价。 */
    public static final BigDecimal FIXED_RATE = PricingPolicy.MAX_DISCOUNT;

    @Override
    public String code() {
        return "FIXED";
    }

    @Override
    public PricingProposal propose(PricingContext context) {
        BigDecimal expectedQty = PricingPolicy.estimateExpectedQty(context.remainQty(), FIXED_RATE, context.daysLeft());
        String reason = String.format(
                "固定价基线：维持原价 ¥%s 不打折（对照组，代表店主不降价的现状）；本批次还剩 %d 天、现存 %s%s",
                PricingPolicy.money(context.originalPrice()),
                context.daysLeft(),
                PricingPolicy.qty(context.remainQty()),
                unit(context));
        return new PricingProposal(FIXED_RATE, expectedQty, PricingPolicy.reason(reason));
    }

    private String unit(PricingContext context) {
        String unit = context.item() == null ? null : context.item().getUnit();
        return unit == null || unit.isBlank() ? "" : unit;
    }
}
