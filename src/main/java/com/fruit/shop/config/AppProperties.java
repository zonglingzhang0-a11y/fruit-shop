package com.fruit.shop.config;

import com.fruit.shop.pricing.PricingPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 业务配置（docs/01-技术方案.md 第 6 节）。
 *
 * <p>已存在的键（application.yml 里显式配置）：{@code app.pricing.active-strategy}、
 * {@code app.pricing.near-expiry-days}、{@code app.push.daily-limit-per-member}、{@code app.push.quiet-hours}。
 * 其余键给出安全默认值，所以就算 yml 里没写也能跑（不修改 resources 也能生效）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Pricing pricing = new Pricing();

    private Push push = new Push();

    private Scan scan = new Scan();

    @Data
    public static class Pricing {

        /** 当前生效策略：FIXED / RULE / MODEL（yml 里写小写也能识别）。 */
        private String activeStrategy = "RULE";

        /** 临期阈值（天）：days_left <= 该值进入临期清单。 */
        private int nearExpiryDays = 2;

        /** MODEL 策略读取的模型产物（Python 侧生成）；文件不存在时自动回退 RULE。 */
        private String modelFile = "./data/seed/pricing_model.json";

        /** 是否允许在模型产物缺失时回退到 RULE。 */
        private boolean modelFallbackToRule = true;

        /**
         * 最低折扣线（docs/07 第 1 节 B）：任何策略、任何手工改价都不得低于该价格比，店主可调。
         * yml 键 {@code app.pricing.min-discount-rate}，默认 0.60。
         */
        private BigDecimal minDiscountRate = PricingPolicy.DEFAULT_MIN_DISCOUNT_RATE;

        /**
         * 同一批次每天最多调价次数（docs/07 第 1 节 B），默认 2；第 3 次调价会被拒绝并给出人话原因。
         * yml 键 {@code app.pricing.max-adjust-per-day}。
         */
        private int maxAdjustPerDay = PricingPolicy.DEFAULT_MAX_ADJUST_PER_DAY;
    }

    @Data
    public static class Push {

        /** 单会员每日推送上限（条）。 */
        private int dailyLimitPerMember = 2;

        /** 静默时段，支持多段逗号分隔，如 {@code 22:00-08:00}。 */
        private String quietHours = "22:00-08:00";

        /** 文案里的门店名。 */
        private String storeName = "街边水果店";
    }

    /**
     * 只读定时扫描（docs/07 第 2 节）：每小时算一次临期判定指标，只写派生字段，
     * 不自动改价、不自动改批次状态、不自动发消息。
     */
    @Data
    public static class Scan {

        /** 是否开启既定时扫描；默认开启。yml 键 {@code app.scan.enabled}。 */
        private boolean enabled = true;

        /**
         * 扫描周期（Spring 6 段 cron：秒 分 时 日 月 周），默认每小时整点。
         * yml 键 {@code app.scan.cron}；{@code BatchScanJob} 的 {@code @Scheduled} 用同一默认值兜底。
         */
        private String cron = "0 0 * * * *";
    }

    /** 归一化成 PricingPolicy 里定义的三个策略码之一。 */
    public String activeStrategyCode() {
        String raw = pricing.getActiveStrategy();
        return raw == null || raw.isBlank() ? "RULE" : raw.trim().toUpperCase();
    }

    public int nearExpiryDays() {
        return pricing.getNearExpiryDays() <= 0 ? PricingPolicy.DEFAULT_NEAR_EXPIRY_DAYS : pricing.getNearExpiryDays();
    }

    /** 最低折扣线（docs/07 第 1 节 B），非法/缺失时回落到 0.60。 */
    public BigDecimal minDiscountRate() {
        BigDecimal rate = pricing.getMinDiscountRate();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            return PricingPolicy.DEFAULT_MIN_DISCOUNT_RATE;
        }
        return rate;
    }

    /** 同批次每天最多调价次数（docs/07 第 1 节 B），非法/缺失时回落到 2。 */
    public int maxAdjustPerDay() {
        return pricing.getMaxAdjustPerDay() <= 0
                ? PricingPolicy.DEFAULT_MAX_ADJUST_PER_DAY
                : pricing.getMaxAdjustPerDay();
    }
}
