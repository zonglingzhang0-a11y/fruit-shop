package com.fruit.shop.pricing;

import com.fruit.shop.pricing.strategy.RuleBasedPricingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯函数级测试：候选折扣档、不涨价、RULE 打分。
 * 不启 Spring 容器（毫秒级），把"业务规则"和"数据库/HTTP"解耦开。
 */
class PricingPolicyTest {

    @Test
    @DisplayName("候选折扣档只有契约里的 6 个档位")
    void candidateDiscountsMatchContract() {
        assertThat(PricingPolicy.CANDIDATE_DISCOUNTS)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("1.00", "0.90", "0.80", "0.70", "0.60", "0.50");
    }

    @Test
    @DisplayName("任意折扣都会对齐到候选档，且不会超过 1.00（不许涨价）")
    void anyDiscountIsClampedIntoCandidateSetAndNeverAboveOne() {
        assertThat(PricingPolicy.toCandidate(new BigDecimal("0.73"))).isEqualByComparingTo("0.70");
        assertThat(PricingPolicy.toCandidate(new BigDecimal("1.40"))).isEqualByComparingTo("1.00");
        assertThat(PricingPolicy.toCandidate(new BigDecimal("0.10"))).isEqualByComparingTo("0.50");
        assertThat(PricingPolicy.toCandidate(null)).isEqualByComparingTo("1.00");
        assertThat(PricingPolicy.isCandidate(PricingPolicy.toCandidate(new BigDecimal("0.66")))).isTrue();
        assertThat(PricingPolicy.isCandidate(new BigDecimal("0.66"))).isFalse();
    }

    @Test
    @DisplayName("建议价 = 原价 × 折扣，四舍五入到分，且永远不超过原价")
    void suggestedPriceNeverExceedsOriginalPrice() {
        assertThat(PricingPolicy.priceOf(new BigDecimal("10.00"), new BigDecimal("0.70")))
                .isEqualByComparingTo("7.00");
        assertThat(PricingPolicy.priceOf(new BigDecimal("19.90"), new BigDecimal("0.70")))
                .isEqualByComparingTo("13.93");
        assertThat(PricingPolicy.priceOf(new BigDecimal("9.99"), new BigDecimal("1.00")))
                .isLessThanOrEqualTo(new BigDecimal("9.99"));
        for (BigDecimal candidate : PricingPolicy.CANDIDATE_DISCOUNTS) {
            BigDecimal price = PricingPolicy.priceOf(new BigDecimal("12.34"), candidate);
            assertThat(price).isLessThanOrEqualTo(new BigDecimal("12.34"));
            assertThat(price).isEqualByComparingTo(new BigDecimal("12.34").multiply(candidate)
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Test
    @DisplayName("RULE 打分：与 pricing_rule.py 自检的典型场景逐个一致（完整边界用例见 PricingRuleContractTest）")
    void ruleScoreMatchesTypicalScenarios() {
        // (剩余天数, 现存, 到货量, 保质期) → 期望折扣、紧迫度、库存压力
        assertRule(7, "40", "40", 7, "1.00", 0, 0);   // 刚到货：新鲜货不打折
        assertRule(5, "40", "40", 7, "1.00", 0, 0);   // 未进临期窗口：不计库存压力
        assertRule(3, "38", "40", 7, "0.70", 1, 3);   // 剩 3 天但库存几乎没动：1 + 3 = 4 分 → 7 折
        assertRule(2, "10", "40", 7, "0.90", 2, 0);   // 临期但只剩 25%：压力 0 → 9 折
        assertRule(2, "35", "40", 7, "0.70", 2, 3);   // 临期且库存压手：2 + 3 = 5 分 → 7 折
        assertRule(1, "5", "40", 7, "0.70", 3, 0);    // 最后可售日：至少 7 折
        assertRule(0, "20", "40", 7, "0.50", 4, 2);   // 今天到期：4 + 2 + 寿命 1 = 7 分 → 5 折（底线另由 PricingService 套）
        assertRule(1, "0", "40", 7, "1.00", 0, 0);    // 无库存：无需定价
        // 看的是"还剩几成"而不是绝对份数：同样剩 25 份，卖掉 75% 的批次只打 9 折，一份没卖的打 7 折
        assertRule(2, "25", "100", 7, "0.90", 2, 0);
        assertRule(2, "25", "25", 7, "0.70", 2, 3);
        for (int days = -2; days <= 12; days++) {
            BigDecimal rate = RuleBasedPricingStrategy.score(days, new BigDecimal("30"), new BigDecimal("40"), 7).rate();
            assertThat(PricingPolicy.isCandidate(rate)).as("days=%d → %s", days, rate).isTrue();
        }
    }

    private static void assertRule(int daysLeft, String remain, String init, int shelfLife,
                                   String rate, int urgency, int pressure) {
        RuleBasedPricingStrategy.RuleScore s = RuleBasedPricingStrategy.score(
                daysLeft, new BigDecimal(remain), new BigDecimal(init), shelfLife);
        assertThat(s.rate()).as("days=%d remain=%s/%s", daysLeft, remain, init).isEqualByComparingTo(rate);
        assertThat(s.urgency()).isEqualTo(urgency);
        assertThat(s.pressure()).isEqualTo(pressure);
    }

    @Test
    @DisplayName("折扣标签是人话：0.70 → 7折、1.00 → 原价")
    void discountLabelsAreReadable() {
        assertThat(PricingPolicy.discountLabel(new BigDecimal("0.70"))).isEqualTo("7折");
        assertThat(PricingPolicy.discountLabel(new BigDecimal("0.60"))).isEqualTo("6折");
        assertThat(PricingPolicy.discountLabel(new BigDecimal("1.00"))).isEqualTo("原价");
    }

    @Test
    @DisplayName("期望销量粗估：不超过现存量、折扣越深估计越高")
    void expectedQtyEstimateIsBoundedByStock() {
        BigDecimal remain = new BigDecimal("12");
        BigDecimal light = PricingPolicy.estimateExpectedQty(remain, new BigDecimal("0.90"), 2);
        BigDecimal deep = PricingPolicy.estimateExpectedQty(remain, new BigDecimal("0.50"), 2);
        assertThat(light).isLessThanOrEqualTo(remain);
        assertThat(deep).isLessThanOrEqualTo(remain);
        assertThat(deep).isGreaterThanOrEqualTo(light);
        assertThat(PricingPolicy.estimateExpectedQty(BigDecimal.ZERO, new BigDecimal("0.50"), 0))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("最低折扣线（纯函数）：低于底线抬到最近候选档并留痕；恰好等于底线视为通过")
    void floorClampLiftsDiscountAndKeepsRawTier() {
        // 0.50 触底 → 抬到不低于 0.60 的最小候选档 0.60，raw 记录 0.50
        PricingPolicy.FloorClamp hit = PricingPolicy.applyFloor(new BigDecimal("0.50"), new BigDecimal("0.60"));
        assertThat(hit.rate()).isEqualByComparingTo("0.60");
        assertThat(hit.rawRate()).isEqualByComparingTo("0.50");
        assertThat(hit.floorHit()).isTrue();

        // 边界：恰好等于底线 → 不触底，也不改档
        PricingPolicy.FloorClamp atFloor = PricingPolicy.applyFloor(new BigDecimal("0.60"), new BigDecimal("0.60"));
        assertThat(atFloor.rate()).isEqualByComparingTo("0.60");
        assertThat(atFloor.floorHit()).isFalse();

        // 高于底线 → 原样不动，raw == 实际档位
        PricingPolicy.FloorClamp above = PricingPolicy.applyFloor(new BigDecimal("0.70"), new BigDecimal("0.60"));
        assertThat(above.rate()).isEqualByComparingTo("0.70");
        assertThat(above.rawRate()).isEqualByComparingTo("0.70");
        assertThat(above.floorHit()).isFalse();

        // 底线不是候选档（0.65）→ 抬到最小的 ≥0.65 的候选档 0.70
        assertThat(PricingPolicy.applyFloor(new BigDecimal("0.50"), new BigDecimal("0.65")).rate())
                .isEqualByComparingTo("0.70");

        // 未配置/非法 → 用默认 0.60
        assertThat(PricingPolicy.applyFloor(new BigDecimal("0.50"), null).rate()).isEqualByComparingTo("0.60");
        assertThat(PricingPolicy.applyFloor(new BigDecimal("0.50"), BigDecimal.ZERO).rate())
                .isEqualByComparingTo("0.60");
        assertThat(PricingPolicy.DEFAULT_MIN_DISCOUNT_RATE).isEqualByComparingTo("0.60");
        assertThat(PricingPolicy.DEFAULT_MAX_ADJUST_PER_DAY).isEqualTo(2);

        // 最低可售价 & 价格比：给店主看"最低能卖多少"
        assertThat(PricingPolicy.lowestPriceAtFloor(new BigDecimal("10.00"), new BigDecimal("0.60")))
                .isEqualByComparingTo("6.00");
        assertThat(PricingPolicy.rateOf(new BigDecimal("6.00"), new BigDecimal("10.00")))
                .isEqualByComparingTo("0.6000");
        assertThat(PricingPolicy.rateOf(new BigDecimal("6.00"), BigDecimal.ZERO)).isNull();
    }

    @Test
    @DisplayName("对外价格标签不对齐候选档：0.65 → 6.5折（与策略档位标签区分）")
    void priceRatioLabelDoesNotSnapToCandidateTiers() {
        assertThat(PricingPolicy.priceRatioLabel(new BigDecimal("0.65"))).isEqualTo("6.5折");
        assertThat(PricingPolicy.priceRatioLabel(new BigDecimal("0.60"))).isEqualTo("6折");
        assertThat(PricingPolicy.priceRatioLabel(new BigDecimal("1.00"))).isEqualTo("原价");
        assertThat(PricingPolicy.priceRatioLabel(null)).isEqualTo("-");
        // 策略档位标签仍会向下对齐到候选档
        assertThat(PricingPolicy.discountLabel(new BigDecimal("0.65"))).isEqualTo("6折");
    }

    @Test
    @DisplayName("reason 统一截断到 250 字以内（数据库列是 VARCHAR(255)）")
    void reasonIsTruncated() {
        String longReason = "很长的理由".repeat(100);
        assertThat(PricingPolicy.reason(longReason)).hasSize(PricingPolicy.REASON_MAX_LENGTH);
        assertThat(PricingPolicy.reason("短理由")).isEqualTo("短理由");
        assertThat(List.of(PricingPolicy.reason(longReason)).get(0))
                .isEqualTo(longReason.substring(0, PricingPolicy.REASON_MAX_LENGTH));
    }
}
