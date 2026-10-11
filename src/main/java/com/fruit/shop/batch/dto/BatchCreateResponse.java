package com.fruit.shop.batch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 登记批次响应。前端主用 {@code batchId / batchNo / expireDate}；
 * 其余字段是兼容新增，方便入库成功后直接回显，不必再查一次。
 */
public record BatchCreateResponse(
        Long batchId,
        String batchNo,
        LocalDate expireDate,
        Long itemId,
        String itemName,
        LocalDate arriveDate,
        Integer shelfLifeDays,
        BigDecimal initQty,
        BigDecimal remainQty,
        BigDecimal costPrice,
        String status) {
}
