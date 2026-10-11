package com.fruit.shop.batch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 临期清单行（docs/01-技术方案.md 第 5 节接口 3 + docs/07 第 1 节 A）。
 *
 * <p>字段名与前端约定一致；{@code lossRatePct} 来自商品档案的参考损耗率，用于店主判断"还能不能等"。
 *
 * <p>v2 追加的临期判定指标（freshness / estimatedSellableDays / plannedSaleQty / expectedScrapQty /
 * atRisk）由 {@code BatchMetricsService} 在本次请求内**实时计算**：查询接口不写库，所以数值永远与
 * 当前库存/销量一致；小时级只读扫描会把同一口径落库到 {@code fruit_batch.metrics_updated_at}。
 *
 * @param metricsUpdatedAt 本行指标的本次计算时间（"最近一次计算时间"的口径）
 * @param metricsReason    口径说明（含"样本不足 3 天 → 品类兜底"这类说明）；这是接口字段，不落库
 */
public record NearExpiryRow(
        Long batchId,
        String batchNo,
        Long itemId,
        String itemName,
        String categoryName,
        String unit,
        LocalDate arriveDate,
        LocalDate expireDate,
        Integer daysLeft,
        BigDecimal remainQty,
        BigDecimal costPrice,
        BigDecimal basePrice,
        BigDecimal lossRatePct,
        BigDecimal freshness,
        BigDecimal estimatedSellableDays,
        BigDecimal plannedSaleQty,
        BigDecimal expectedScrapQty,
        Boolean atRisk,
        LocalDateTime metricsUpdatedAt,
        String metricsReason) {
}
