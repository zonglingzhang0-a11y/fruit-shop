package com.fruit.shop.pricing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 定价建议（系统给出的建议，不是最终售价；店主在 pricing_decision 里确认）。 */
@Data
@TableName("pricing_suggestion")
public class PricingSuggestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate bizDate;

    private Long itemId;

    private Long batchId;

    /** 剩余可售天数 */
    private Integer daysLeft;

    private BigDecimal remainQty;

    /** 原价（折扣基准） */
    private BigDecimal originalPrice;

    private BigDecimal suggestedPrice;

    /** 折扣系数 0.50 ~ 1.00 */
    private BigDecimal discountRate;

    /** FIXED / RULE / MODEL */
    private String strategy;

    /**
     * 实际给出价格的策略：通常等于 {@link #strategy}；请求 MODEL 但模型产物缺失/过期/没覆盖该批次而回退规则时为 RULE。
     * 效果看板按它归属，避免把规则定价的结果算到模型头上。旧数据为空时按 strategy 理解。
     */
    private String effectiveStrategy;

    /** 模型给出的期望销量 */
    private BigDecimal expectedQty;

    /** 给店主看的人话理由 */
    private String reason;

    // ------------------------------------------------------------------ v2 定价约束留痕
    // 对应 docs/07 第 1 节 B 与参考架构《定价引擎》第 4 步"最低折扣线 + 每日调价上限"。

    /** 建议价是否被"最低折扣线"拦住（本来想更低但触底）。 */
    private Boolean priceFloorHit;

    /** 被约束前的原始折扣档位（说明"本可以更低"）。 */
    private BigDecimal rawDiscountRate;

    /** 该批次当天第几次调价（1/2/3…），受 app.pricing.max-adjust-per-day 约束。 */
    private Integer adjustSeq;

    // 建议生成时的临期判定指标快照（事后可复核"为什么这天建议打折"）。
    private BigDecimal freshness;

    private BigDecimal estimatedSellableDays;

    private BigDecimal expectedScrapQty;

    private LocalDateTime createdAt;
}
