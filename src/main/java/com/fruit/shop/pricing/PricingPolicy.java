package com.fruit.shop.pricing;

import com.fruit.shop.support.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 定价口径工具类（业务规则的唯一实现处，见 docs/01-技术方案.md 第 3 节第 3、4 条）。
 *
 * <ul>
 *   <li>候选折扣档只有 {1.00, 0.90, 0.80, 0.70, 0.60, 0.50}，算法在档位里挑，不做连续搜索；</li>
 *   <li>折扣系数必须 &lt;= 1.0：这是"清库存"而不是"涨价"，任何策略都不允许给出高于原价的建议价。</li>
 * </ul>
 */
public final class PricingPolicy {

    /** 默认候选折扣档（相对原价）。 */
    public static final List<BigDecimal> CANDIDATE_DISCOUNTS = List.of(
            new BigDecimal("1.00"),
            new BigDecimal("0.90"),
            new BigDecimal("0.80"),
            new BigDecimal("0.70"),
            new BigDecimal("0.60"),
            new BigDecimal("0.50"));

    public static final BigDecimal MIN_DISCOUNT = new BigDecimal("0.50");

    public static final BigDecimal MAX_DISCOUNT = new BigDecimal("1.00");

    /** 临期阈值默认值（天），与 docs/01 第 6 节 app.pricing.near-expiry-days 一致。 */
    public static final int DEFAULT_NEAR_EXPIRY_DAYS = 2;

    /**
     * 最低折扣线默认值（docs/07 第 1 节 B）：任何策略、任何手工改价都不得低于该价格比。
     * yml 键 {@code app.pricing.min-discount-rate}。
     */
    public static final BigDecimal DEFAULT_MIN_DISCOUNT_RATE = new BigDecimal("0.60");

    /** 同批次每天最多调价次数默认值（docs/07 第 1 节 B）。yml 键 {@code app.pricing.max-adjust-per-day}。 */
    public static final int DEFAULT_MAX_ADJUST_PER_DAY = 2;

    /** reason 列是 VARCHAR(255)，中文按字符数截断，留出安全余量。 */
    public static final int REASON_MAX_LENGTH = 250;

    /** 折扣-销量弹性假设（占位常数，可被 MODEL 产物覆盖）：价格每降 1%，销量约升 0.8%。 */
    public static final double ELASTICITY_ASSUMPTION = 0.8;

    private PricingPolicy() {
    }

    public static boolean isCandidate(BigDecimal rate) {
        if (rate == null) {
            return false;
        }
        return CANDIDATE_DISCOUNTS.stream().anyMatch(candidate -> candidate.compareTo(rate) == 0);
    }

    /** 把任意折扣对齐到候选档：向下取（对顾客更便宜），最低 0.50；&gt;= 1.00 直接钳成 1.00（禁止涨价）。 */
    public static BigDecimal toCandidate(BigDecimal rate) {
        if (rate == null) {
            return MAX_DISCOUNT;
        }
        BigDecimal normalized = rate.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(MAX_DISCOUNT) >= 0) {
            return MAX_DISCOUNT;
        }
        BigDecimal best = MIN_DISCOUNT;
        for (BigDecimal candidate : CANDIDATE_DISCOUNTS) {
            if (candidate.compareTo(normalized) <= 0 && candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 最低折扣线的留痕结果（docs/07 第 1 节 B）。
     *
     * @param rate     约束后的实际折扣档（一定 &ge; 底线）
     * @param rawRate  约束前的原始档位（"本来想给多少"）
     * @param floorHit 是否被底线拦住（rawRate &lt; rate 时为 true）
     */
    public record FloorClamp(BigDecimal rate, BigDecimal rawRate, boolean floorHit) {
    }

    /**
     * 对任意策略给出的折扣套用"最低折扣线"（docs/07 第 1 节 B）：
     * 折扣系数不得低于 {@code minDiscountRate}；触底时把档位抬到"不低于底线的最小候选档"，
     * 并保留原始档位供解释"本可以更低"。边界取闭区间：恰好等于底线视为通过（floorHit=false）。
     *
     * @param proposedRate    策略原始折扣（任意值，会先对齐到候选档）
     * @param minDiscountRate 最低折扣线，null/非法时用 {@link #DEFAULT_MIN_DISCOUNT_RATE}
     */
    public static FloorClamp applyFloor(BigDecimal proposedRate, BigDecimal minDiscountRate) {
        BigDecimal raw = toCandidate(proposedRate);
        BigDecimal floor = minDiscountRate == null || minDiscountRate.compareTo(BigDecimal.ZERO) <= 0
                || minDiscountRate.compareTo(BigDecimal.ONE) > 0
                ? DEFAULT_MIN_DISCOUNT_RATE
                : minDiscountRate;
        if (raw.compareTo(floor) >= 0) {
            return new FloorClamp(raw, raw, false);
        }
        BigDecimal lifted = MAX_DISCOUNT;
        for (BigDecimal candidate : CANDIDATE_DISCOUNTS) {
            if (candidate.compareTo(floor) >= 0 && candidate.compareTo(lifted) < 0) {
                lifted = candidate;
            }
        }
        return new FloorClamp(lifted, raw, true);
    }

    /** 价格比 = 价格 ÷ 原价（4 位小数）；原价缺失/为 0 时返回 null。 */
    public static BigDecimal rateOf(BigDecimal price, BigDecimal originalPrice) {
        if (price == null || originalPrice == null || originalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return price.divide(originalPrice, 4, RoundingMode.HALF_UP);
    }

    /** 最低折扣线对应的最低可售价（原价 × 底线，四舍五入到分）；用于"改价被拒"时告诉店主最低能卖多少。 */
    public static BigDecimal lowestPriceAtFloor(BigDecimal originalPrice, BigDecimal minDiscountRate) {
        BigDecimal floor = minDiscountRate == null || minDiscountRate.compareTo(BigDecimal.ZERO) <= 0
                || minDiscountRate.compareTo(BigDecimal.ONE) > 0
                ? DEFAULT_MIN_DISCOUNT_RATE
                : minDiscountRate;
        if (originalPrice == null) {
            return null;
        }
        return round2(originalPrice.multiply(floor));
    }

    /**
     * 价格比的人话标签，用于"店主确认后的对外价"（{@code /api/batches/on-sale}）：
     * 不做候选档对齐，0.65 → "6.5折"、0.60 → "6折"、1.00 → "原价"。
     * 与 {@link #discountLabel(BigDecimal)}（策略档位标签，会对齐到候选档）区分开。
     */
    public static String priceRatioLabel(BigDecimal rate) {
        if (rate == null) {
            return "-";
        }
        BigDecimal normalized = rate.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(MAX_DISCOUNT) >= 0) {
            return "原价";
        }
        BigDecimal tenths = normalized.multiply(BigDecimal.TEN).stripTrailingZeros();
        return tenths.toPlainString() + "折";
    }

    /** 建议价 = 原价 × 折扣，保留 2 位小数；并保证不超过原价（第 3 节第 4 条）。 */
    public static BigDecimal priceOf(BigDecimal originalPrice, BigDecimal rate) {
        if (originalPrice == null) {
            throw BusinessException.badRequest("商品原价缺失，无法计算建议价");
        }
        BigDecimal original = round2(originalPrice);
        BigDecimal price = original.multiply(toCandidate(rate)).setScale(2, RoundingMode.HALF_UP);
        return price.compareTo(original) > 0 ? original : price;
    }

    public static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 0.70 → "7折"；0.85 → "8.5折"；1.00 → "原价"。 */
    public static String discountLabel(BigDecimal rate) {
        BigDecimal normalized = toCandidate(rate);
        if (normalized.compareTo(MAX_DISCOUNT) == 0) {
            return "原价";
        }
        BigDecimal tenths = normalized.multiply(BigDecimal.TEN).stripTrailingZeros();
        return tenths.toPlainString() + "折";
    }

    /**
     * 期望销量的粗略估算（占位启发式，等 Python 侧 data/seed/pricing_model.json 覆盖）。
     *
     * <p>公式：{@code min(remain_qty, remain_qty × (1/折扣)^0.8 × 可售窗口系数)}；
     * 可售窗口系数按剩余天数折算：0 天 0.60、1 天 0.75、2 天 0.90、3 天及以上 1.00。
     * 这只是给店主一个量级参考，标注在 reason 里，不参与任何资金/库存计算。
     */
    public static BigDecimal estimateExpectedQty(BigDecimal remainQty, BigDecimal rate, int daysLeft) {
        if (remainQty == null || remainQty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        double discount = Math.max(toCandidate(rate).doubleValue(), 0.01);
        double uplift = Math.pow(1.0 / discount, ELASTICITY_ASSUMPTION);
        double window = switch (Math.max(daysLeft, 0)) {
            case 0 -> 0.60;
            case 1 -> 0.75;
            case 2 -> 0.90;
            default -> 1.00;
        };
        BigDecimal estimate = BigDecimal.valueOf(remainQty.doubleValue() * uplift * window)
                .setScale(0, RoundingMode.HALF_UP);
        return estimate.compareTo(remainQty) > 0 ? round2(remainQty) : estimate;
    }

    /** 金额展示：统一两位小数，避免文案里出现 7.0 元。 */
    public static String money(BigDecimal value) {
        return value == null ? "-" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 数量展示：整数就不带小数点。 */
    public static String qty(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public static String reason(String text) {
        return truncate(text, REASON_MAX_LENGTH);
    }
}
