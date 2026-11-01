package com.fruit.shop.batch.dto;

import java.math.BigDecimal;

/**
 * 报废人工登记结果。
 *
 * @param scrapQty      本次登记的报废量
 * @param totalScrapQty 该批次累计报废量
 * @param remainQty     登记后的现存
 * @param status        登记后的批次状态（整批报废为 SCRAPPED，部分报废仍为 ON_SALE）
 * @param strategy      计入哪个策略的当日损耗（推断不出时为 null，不写统计）
 * @param statUpdated   是否写入了策略日统计
 * @param message       给人看的一句话结果
 */
public record BatchScrapResponse(
        Long batchId,
        String batchNo,
        BigDecimal scrapQty,
        BigDecimal totalScrapQty,
        BigDecimal remainQty,
        String status,
        String strategy,
        boolean statUpdated,
        String message) {
}
