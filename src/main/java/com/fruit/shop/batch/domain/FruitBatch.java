package com.fruit.shop.batch.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 到货批次（临期台账的载体）。
 *
 * 业务口径（见 docs/01-技术方案.md 第 3 节）：
 * - 到货当天 remain_qty = init_qty
 * - 剩余可售天数 days_left = expire_date - biz_date
 * - 批次按"品类 + 到货日"登记，不逐箱登记（真实店主的操作负担）
 */
@Data
@TableName("fruit_batch")
public class FruitBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;

    private String batchNo;

    private LocalDate arriveDate;

    private LocalDate expireDate;

    private BigDecimal initQty;

    /** 现存可售量 */
    private BigDecimal remainQty;

    /** 该批次实际进货价 */
    private BigDecimal costPrice;

    /** 累计报废量 */
    private BigDecimal scrapQty;

    /** ON_SALE / SOLD_OUT / SCRAPPED */
    private String status;

    // ------------------------------------------------------------------ v2 临期判定指标
    // 口径见 docs/07 第 1 节 A；由 BatchMetricsService 计算，只读定时扫描负责落库。
    // 这些列全是"算出来的派生值"，不是业务状态：改它们不算改业务（docs/07 第 2 节）。

    /** 新鲜度 = 剩余天数 ÷ 标准保质期，0~1，越小越不新鲜。 */
    private BigDecimal freshness;

    /** 预计可售天数 = 剩余库存 ÷ 近 7 天日均销量（卖不动时为 null）。 */
    private BigDecimal estimatedSellableDays;

    /** 到期前预计销量 = min(剩余库存, 日均销量 × 剩余天数)。 */
    private BigDecimal plannedSaleQty;

    /** 预计报废量 = 剩余库存 − 预计销量。 */
    private BigDecimal expectedScrapQty;

    /** 是否有风险 = 预计可售天数 > 剩余天数（卖不动时视为有风险）。 */
    private Boolean atRisk;

    /** 上述指标最近一次计算/落库时间。 */
    private LocalDateTime metricsUpdatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
