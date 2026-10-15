package com.fruit.shop.pricing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 模型输入快照（{@code GET /api/pricing/model-input}），字段名即 {@code scripts/export_model_suggestions.py} 读取的契约。
 *
 * @param bizDate     要做定价决策的业务日
 * @param historyFrom 历史窗口起点（含）
 * @param historyTo   历史窗口终点（含，= bizDate 前一天）
 * @param historyDays 窗口天数
 * @param salesFrom   sales 的起点（含）：窗口起点与窗口内批次最早到货日中较早的那个，用于重建期初库存
 * @param items       单品档案（原价、成本价）
 * @param batches     窗口内可能在售过的批次台账（到货量、现存、到期日）
 * @param sales       窗口内按 业务日 × 批次 汇总的净销量与营收
 * @param note        口径说明
 */
public record ModelInputResponse(
        LocalDate bizDate,
        LocalDate historyFrom,
        LocalDate historyTo,
        int historyDays,
        LocalDate salesFrom,
        List<Item> items,
        List<Batch> batches,
        List<DailySale> sales,
        String note) {

    public record Item(Long itemId, String itemCode, String itemName, BigDecimal basePrice, BigDecimal costPrice) {
    }

    public record Batch(Long batchId, String batchNo, Long itemId, String itemCode, LocalDate arriveDate,
                        LocalDate expireDate, BigDecimal initQty, BigDecimal remainQty, BigDecimal costPrice,
                        String status) {
    }

    public record DailySale(LocalDate bizDate, Long batchId, String itemCode, BigDecimal qty, BigDecimal revenue) {
    }
}
