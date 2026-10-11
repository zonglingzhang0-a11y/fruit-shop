package com.fruit.shop.pricing.strategy;

import com.fruit.shop.config.AppProperties;
import com.fruit.shop.pricing.PricingPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * FIXED：固定折扣档（默认 7 折，可用 {@code app.pricing.fixed-discount} 调整）。
 *
 * <p>刻意不看剩余天数、不看存量：它就是"店主拍脑袋一刀切"的数字化版本，
 * 用来给 RULE/MODEL 做效果对比的基线。折扣仍会向下对齐到候选档。
 */
@Component
public class FixedDiscountPricingStrategy implements PricingStrategy {

    private final AppProperties props;

    public FixedDiscountPricingStrategy(AppProperties props) {
        this.props = props;
    }

    @Override
    public String code() {
        return "FIXED";
    }

    @Override
    public PricingProposal propose(PricingContext context) {
        BigDecimal rate = PricingPolicy.toCandidate(props.getPricing().getFixedDiscount());
        BigDecimal price = PricingPolicy.priceOf(context.originalPrice(), rate);
        BigDecimal expectedQty = PricingPolicy.estimateExpectedQty(context.remainQty(), rate, context.daysLeft());
        String reason = String.format(
                "固定清仓档：所有临期批次统一 %s，原价 ¥%s → 建议价 ¥%s；本批次还剩 %d 天、现存 %s%s。"
                        + "该档位不看剩余天数与客流，作为效果对比的基线。",
                PricingPolicy.discountLabel(rate),
                PricingPolicy.money(context.originalPrice()),
                PricingPolicy.money(price),
                context.daysLeft(),
                PricingPolicy.qty(context.remainQty()),
                unit(context));
        if (price.compareTo(context.costPrice()) < 0) {
            reason += "注意：已低于批次成本 ¥" + PricingPolicy.money(context.costPrice()) + "，属清仓让利。";
        }
        return new PricingProposal(rate, expectedQty, PricingPolicy.reason(reason));
    }

    private String unit(PricingContext context) {
        String unit = context.item() == null ? null : context.item().getUnit();
        return unit == null || unit.isBlank() ? "" : unit;
    }
}
