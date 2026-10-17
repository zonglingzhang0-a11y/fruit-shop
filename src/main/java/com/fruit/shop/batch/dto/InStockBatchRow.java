package com.fruit.shop.batch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 某单品当前有库存的在售批次（销售回流录入页"选批次"用，Issue #4）。
 *
 * <p>顺序与销售回流不指定批次时的自动挑选（FIFO）一致：最早到期的排第一，所以第一行就是"不选批次时会扣的那批"。
 *
 * @param daysLeft  相对 bizDate 的剩余天数（可能为负：已过期但还没被扫描标记报废）
 * @param basePrice 单品原价，录入成交价时的默认值参考
 */
public record InStockBatchRow(
        Long batchId,
        String batchNo,
        Long itemId,
        String itemName,
        String unit,
        LocalDate arriveDate,
        LocalDate expireDate,
        Integer daysLeft,
        BigDecimal remainQty,
        BigDecimal basePrice) {
}
