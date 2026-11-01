package com.fruit.shop.pricing.strategy;

import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.pricing.PricingPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

/**
 * RULE：规则版基线（可解释、零依赖，默认生效策略）——把"店主经验"写成一张可人工复核的打分表。
 *
 * <p>与离线评测用的 {@code scripts/pricing_rule.py::rule_discount} 逐条一致，评测报告里 RULE 的数字就是这套规则跑出来的。
 * 契约测试 {@code PricingRuleContractTest} 拿 Python 导出的边界用例逐条比对，任何一边改了规则另一边不跟，CI 就会失败。
 * <ol>
 *   <li>紧迫度：剩余 0 天及以下 4 分、1 天 3 分、2 天 2 分、3 天 1 分、4 天及以上 0 分；</li>
 *   <li>库存压力（只在剩余 ≤ 3 天时计入，刚到货的满仓批次不算压力）：现存 ÷ 到货量 ≥ 70% 3 分、≥ 50% 2 分、
 *       ≥ 30% 1 分；拿不到到货量时按 20 份满仓折算；</li>
 *   <li>剩余寿命（剩余天数 ÷ 保质期）≤ 20% 再加 1 分；</li>
 *   <li>总分查表：0 分原价，1~2 分 9 折，3 分 8 折，4~5 分 7 折，6 分 6 折，7~8 分 5 折；</li>
 *   <li>护栏：刚到货（剩余天数 ≥ 保质期且 &gt; 3 天）原价；最后可售日（剩余 ≤ 1 天）至少 7 折。</li>
 * </ol>
 * 最低折扣线由 {@code PricingService} 对所有策略统一套用（与 Python 的"护栏 3"同口径）。
 * 比例用 double 计算，与 Python 的浮点比较逐位一致（边界如 12/40 = 0.30 两边判定相同）。
 */
@Component
public class RuleBasedPricingStrategy implements PricingStrategy {

    /** 库存压力只在剩余天数 ≤ 该值时计入（pricing_rule.PRESSURE_WINDOW_DAYS）。 */
    static final int PRESSURE_WINDOW_DAYS = 3;
    /** 拿不到到货量时的"经验满仓量"（pricing_rule.stock_pressure_score）。 */
    static final double FULL_STOCK_FALLBACK_QTY = 20.0;
    /** 最后可售日至少打到这一档（pricing_rule.LAST_CHANCE_RATE）。 */
    static final BigDecimal LAST_CHANCE_RATE = new BigDecimal("0.70");
    /** 总分 0~8 → 折扣档（pricing_rule.SCORE_TO_RATE）。 */
    private static final BigDecimal[] SCORE_TO_RATE = {
            new BigDecimal("1.00"), new BigDecimal("0.90"), new BigDecimal("0.90"),
            new BigDecimal("0.80"), new BigDecimal("0.70"), new BigDecimal("0.70"),
            new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.50")};

    /**
     * 一次打分的结果和中间量（理由文案与测试共用）。
     *
     * @param rate       规则给出的折扣档（未套最低折扣线）
     * @param urgency    紧迫度 0~4
     * @param pressure   库存压力 0~3
     * @param stockRatio 现存 ÷ 到货量（拿不到到货量时按 20 份折算；剩余 &gt; 3 天不计压力时为 null）
     * @param lifeBonus  剩余寿命 ≤ 20% 时加的 1 分
     * @param lifeRatio  剩余天数 ÷ 保质期（拿不到保质期时为 null）
     * @param score      总分 0~8
     * @param guard      触发的护栏（没有则为空串）
     */
    public record RuleScore(BigDecimal rate, int urgency, int pressure, Double stockRatio, boolean lifeBonus,
                            Double lifeRatio, int score, String guard) {
    }

    /** 与 {@code pricing_rule.rule_discount} 同一算法（不含最低折扣线）。 */
    public static RuleScore score(int daysLeft, BigDecimal remainQty, BigDecimal initQty, Integer shelfLifeDays) {
        double remain = remainQty == null ? 0.0 : remainQty.doubleValue();
        if (remain <= 0) {
            return new RuleScore(PricingPolicy.MAX_DISCOUNT, 0, 0, null, false, null, 0, "无现存可售量，无需定价");
        }
        int urgency = daysLeft <= 0 ? 4 : daysLeft == 1 ? 3 : daysLeft == 2 ? 2 : daysLeft == 3 ? 1 : 0;

        int pressure = 0;
        Double stockRatio = null;
        if (daysLeft <= PRESSURE_WINDOW_DAYS) {
            double init = initQty == null ? 0.0 : initQty.doubleValue();
            stockRatio = init <= 0
                    ? Math.min(remain / FULL_STOCK_FALLBACK_QTY, 1.0)
                    : Math.max(0.0, Math.min(remain / init, 1.0));
            pressure = stockRatio >= 0.70 ? 3 : stockRatio >= 0.50 ? 2 : stockRatio >= 0.30 ? 1 : 0;
        }

        boolean hasShelfLife = shelfLifeDays != null && shelfLifeDays > 0;
        Double lifeRatio = hasShelfLife ? (double) daysLeft / shelfLifeDays : null;
        boolean lifeBonus = lifeRatio != null && lifeRatio <= 0.20;

        int score = Math.max(0, Math.min(urgency + pressure + (lifeBonus ? 1 : 0), SCORE_TO_RATE.length - 1));
        BigDecimal rate = SCORE_TO_RATE[score];
        String guard = "";
        if (hasShelfLife && daysLeft >= shelfLifeDays && daysLeft > PRESSURE_WINDOW_DAYS) {
            rate = PricingPolicy.MAX_DISCOUNT;
            guard = "刚到货未临期，维持原价";
        }
        if (daysLeft <= 1 && rate.compareTo(LAST_CHANCE_RATE) > 0) {
            rate = LAST_CHANCE_RATE;
            guard = "已是最后可售日，至少 7 折";
        }
        return new RuleScore(rate, urgency, pressure, stockRatio, lifeBonus, lifeRatio, score, guard);
    }

    /** 批次自己的保质期（到期日 − 到货日）；日期缺失时退回单品档案的保质期。仿真数据里两者相等。 */
    public static Integer shelfLifeOf(FruitBatch batch, FruitItem item) {
        if (batch != null && batch.getArriveDate() != null && batch.getExpireDate() != null) {
            long span = ChronoUnit.DAYS.between(batch.getArriveDate(), batch.getExpireDate());
            if (span > 0) {
                return (int) span;
            }
        }
        return item == null ? null : item.getShelfLifeDays();
    }

    /** 对一个批次打分（推送兜底折扣也用它，保证与定价建议同口径）。 */
    public static RuleScore score(FruitBatch batch, FruitItem item, int daysLeft) {
        return score(daysLeft, batch.getRemainQty(), batch.getInitQty(), shelfLifeOf(batch, item));
    }

    @Override
    public String code() {
        return "RULE";
    }

    @Override
    public PricingProposal propose(PricingContext context) {
        int daysLeft = context.daysLeft();
        FruitBatch batch = context.batch();
        RuleScore s = score(daysLeft, context.remainQty(), batch.getInitQty(), shelfLifeOf(batch, context.item()));
        BigDecimal expectedQty = PricingPolicy.estimateExpectedQty(context.remainQty(), s.rate(), daysLeft);
        String unit = unit(context);

        // 理由只写规则自己的打分与档位；最终挂价（可能被最低折扣线抬高）、是否低于成本由 PricingService 统一追加
        StringBuilder reason = new StringBuilder("规则打分：");
        reason.append(daysText(daysLeft)).append("，紧迫度 ").append(s.urgency()).append(" 分");
        if (s.stockRatio() != null) {
            reason.append("；现存 ").append(PricingPolicy.qty(context.remainQty())).append(unit);
            BigDecimal init = batch.getInitQty();
            if (init != null && init.signum() > 0) {
                reason.append(" / 到货 ").append(PricingPolicy.qty(init)).append(unit)
                        .append("，还剩 ").append(percent(s.stockRatio()));
            } else {
                reason.append("（无到货量，按 20").append(unit).append(" 满仓折算）");
            }
            reason.append("，库存压力 ").append(s.pressure()).append(" 分");
        }
        if (s.lifeBonus()) {
            reason.append("；剩余寿命仅 ").append(percent(s.lifeRatio())).append("，加 1 分");
        }
        reason.append("；合计 ").append(s.score()).append(" 分");
        if (!s.guard().isEmpty()) {
            reason.append("，").append(s.guard());
        }
        reason.append(" → 规则档 ").append(PricingPolicy.discountLabel(s.rate()))
                .append("；预计可销 ").append(PricingPolicy.qty(expectedQty)).append(unit)
                .append("（按弹性 0.8 粗估，仅供量级参考）");
        return new PricingProposal(s.rate(), expectedQty, PricingPolicy.reason(reason.toString()));
    }

    private static String daysText(int daysLeft) {
        if (daysLeft < 0) {
            return "已过期 " + (-daysLeft) + " 天";
        }
        return switch (daysLeft) {
            case 0 -> "今天到期";
            case 1 -> "明天到期";
            default -> "还剩 " + daysLeft + " 天";
        };
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    private String unit(PricingContext context) {
        String unit = context.item() == null ? null : context.item().getUnit();
        return unit == null || unit.isBlank() ? "" : unit;
    }
}
