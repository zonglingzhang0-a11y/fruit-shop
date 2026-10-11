package com.fruit.shop.item.dto;

import java.math.BigDecimal;

/** 商品档案视图：登记批次/生成建议时前端要用来选品。 */
public record ItemView(
        Long id,
        String itemCode,
        String itemName,
        String categoryName,
        String unit,
        BigDecimal costPrice,
        BigDecimal basePrice,
        Integer shelfLifeDays,
        BigDecimal lossRatePct,
        String status) {
}
