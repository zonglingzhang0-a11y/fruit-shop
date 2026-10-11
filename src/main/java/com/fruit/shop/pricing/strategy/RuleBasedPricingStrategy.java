package com.fruit.shop.pricing.strategy;

import com.fruit.shop.config.AppProperties;
import com.fruit.shop.pricing.PricingPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * RULE：规则版基线（可解释、零依赖，默认生效策略）。
 *
 * <p>规则（写死在一处，便于答辩讲清楚）：
 * <ol>
 *   <li>按剩余天数分档：0 天及以下 5 折、1 天 6 折、2 天 7 折、3 天 8 折、4 天及以上 9 折；</li>
 *   <li>存量压力：现存可售量 &gt; {@code app.pricing.pressure-qty-threshold}（默认 20）时再降一档，
 *       最低 5 折——卖不完就是损耗，宁可多让一点利。</li>
 * </ol>
 */
@Component
public class RuleBasedPricingStrategy implements PricingStrategy {

    private final AppProperties props;

    public RuleBasedPricingStrategy(AppProperties props) {
        this.props = props;
    }

    /** 剩余天数 → 基准折扣档（公开静态方法，测试直接断言映射，不经过数据库）。 */
    public static BigDecimal baseDiscount(int daysLeft) {
        if (daysLeft <= 0) {
            return new BigDecimal("0.50");
        }
        return switch (daysLeft) {
            case 1 -> new BigDecimal("0.60");
            case 2 -> new BigDecimal("0.70");
            case 3 -> new BigDecimal("0.80");
            default -> new BigDecimal("0.90");
        };
    }

    /** 剩余天数 → 该档位的解释文字。 */
    public static String baseRuleText(int daysLeft) {
        if (daysLeft <= 0) {
            // 不写死"5 折"：实际挂价还要过"最低折扣线"（docs/07 第 1 节 B），由 PricingService 追加说明。
            return "今天到期或已过期，按规则走最深档清仓";
        }
        return switch (daysLeft) {
            case 1 -> "明天到期，按规则降到 6 折";
            case 2 -> "还剩 2 天，按规则降到 7 折";
            case 3 -> "还剩 3 天，按规则先降到 8 折观察";
            default -> "剩余天数还多，按规则只做 9 折小促";
        };
    }

    @Override
    public String code() {
        return "RULE";
    }

    @Override
    public PricingProposal propose(PricingContext context) {
        int daysLeft = context.daysLeft();
        BigDecimal rate = baseDiscount(daysLeft);
        boolean pressured = false;
        BigDecimal threshold = props.getPricing().getPressureQtyThreshold();
        if (threshold != null && context.remainQty() != null
                && context.remainQty().compareTo(threshold) > 0
                && rate.compareTo(PricingPolicy.MIN_DISCOUNT) > 0) {
            rate = PricingPolicy.oneStepLower(rate);
            pressured = true;
        }
        BigDecimal price = PricingPolicy.priceOf(context.originalPrice(), rate);
        BigDecimal expectedQty = PricingPolicy.estimateExpectedQty(context.remainQty(), rate, daysLeft);

        StringBuilder reason = new StringBuilder();
        reason.append(baseRuleText(daysLeft));
        if (pressured) {
            reason.append("；现存 ").append(PricingPolicy.qty(context.remainQty()))
                    .append(unit(context)).append("、存量偏大再降一档");
        }
        reason.append("：¥").append(PricingPolicy.money(context.originalPrice()))
                .append(" → ¥").append(PricingPolicy.money(price))
                .append("（").append(PricingPolicy.discountLabel(rate)).append("）")
                .append("；到期日 ").append(context.batch().getExpireDate())
                .append("，现存 ").append(PricingPolicy.qty(context.remainQty())).append(unit(context))
                .append("，预计可销 ").append(PricingPolicy.qty(expectedQty)).append(unit(context))
                .append("（按弹性 0.8、剩余天数窗口折算的粗估，仅供量级参考）");
        if (price.compareTo(context.costPrice()) < 0) {
            reason.append("；已低于批次成本 ¥").append(PricingPolicy.money(context.costPrice())).append("，属清仓让利");
        }
        return new PricingProposal(rate, expectedQty, PricingPolicy.reason(reason.toString()));
    }

    private String unit(PricingContext context) {
        String unit = context.item() == null ? null : context.item().getUnit();
        return unit == null || unit.isBlank() ? "" : unit;
    }
}
