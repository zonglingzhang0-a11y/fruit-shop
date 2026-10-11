package com.fruit.shop.batch;

import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.mapper.SalesRecordMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 临期判定相关测试的夹具（放在自己的测试包里，不改 support/ShopFixtures）。
 *
 * <p>造流水时一律 {@code source=TEST}，且只挂在自己造的单品/批次上，和种子数据、其他测试互不干扰。
 */
public final class BatchMetricsFixtures {

    private BatchMetricsFixtures() {
    }

    /** 一条正销量流水。 */
    public static SalesRecord sale(SalesRecordMapper mapper, LocalDate bizDate, Long itemId, Long batchId, String qty) {
        return sale(mapper, bizDate, itemId, batchId, new BigDecimal(qty), false);
    }

    /** 一条流水；{@code isReturn=true} 表示退货（数量存正数，净销量里做减项）。 */
    public static SalesRecord sale(SalesRecordMapper mapper, LocalDate bizDate, Long itemId, Long batchId,
                                   BigDecimal qty, boolean isReturn) {
        SalesRecord record = new SalesRecord();
        record.setBizDate(bizDate);
        record.setSaleTime(bizDate.atTime(10, 0));
        record.setItemId(itemId);
        record.setBatchId(batchId);
        record.setQuantity(qty.abs());
        record.setUnitPrice(new BigDecimal("10.00"));
        record.setIsDiscount(false);
        record.setIsReturn(isReturn);
        record.setSource("TEST");
        mapper.insert(record);
        return record;
    }
}
