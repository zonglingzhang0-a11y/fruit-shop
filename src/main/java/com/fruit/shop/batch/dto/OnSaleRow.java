package com.fruit.shop.batch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 顾客端可见的"当日特价"行（顾客端 H5 特价页的数据源，docs/07 第 6 项）。
 *
 * <p><b>只包含店主已经确认过的价格</b>：{@code finalPrice} 取自 {@code pricing_decision.final_price}
 * （店主实际定的价），不是系统建议价——系统定位是决策辅助，店主没点头的价格不对外露出。
 * 不含任何会员/个人信息字段。
 *
 * @param originalPrice 原价（{@code fruit_item.base_price}）
 * @param finalPrice    店主确认后的最终价（{@code pricing_decision.final_price}）
 * @param discountRate  价格比 = finalPrice ÷ originalPrice（4 位小数，不是候选档）
 * @param discountLabel 价格比的人话标签，如 "6折"/"6.5折"/"原价"
 * @param decisionAt    店主确认时间
 */
public record OnSaleRow(
        Long batchId,
        String batchNo,
        Long itemId,
        String itemName,
        String categoryName,
        String unit,
        BigDecimal originalPrice,
        BigDecimal finalPrice,
        BigDecimal discountRate,
        String discountLabel,
        Integer daysLeft,
        LocalDate expireDate,
        BigDecimal freshness,
        Boolean atRisk,
        BigDecimal remainQty,
        LocalDateTime decisionAt,
        Long suggestionId,
        Long decisionId) {
}
