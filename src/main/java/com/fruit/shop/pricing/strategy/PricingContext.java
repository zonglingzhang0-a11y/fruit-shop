package com.fruit.shop.pricing.strategy;

import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.item.domain.FruitItem;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 定价输入上下文：策略只依赖这些字段，方便单测直接构造（不必起 Spring 容器）。
 *
 * @param item          单品档案
 * @param batch         到货批次（提供 remain_qty / 批次成本价）
 * @param bizDate       业务日
 * @param daysLeft      剩余可售天数（已按 docs/01 第 3 节第 1 条算好）
 * @param remainQty     现存可售量
 * @param originalPrice 原价（折扣基准）
 * @param costPrice     批次实际进货价
 * @param categoryName  品类（用于文案/选人，不参与定价）
 */
public record PricingContext(
        FruitItem item,
        FruitBatch batch,
        LocalDate bizDate,
        int daysLeft,
        BigDecimal remainQty,
        BigDecimal originalPrice,
        BigDecimal costPrice,
        String categoryName) {
}
