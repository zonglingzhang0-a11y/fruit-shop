package com.fruit.shop.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售回流响应（前端主用 {@code remainQty / batchStatus}）。
 *
 * @param soldQty      实际扣减/回补的数量（超过现存量的部分会被截断，见 {@code overSell}）
 * @param overSell     请求数量超过现存量（已按现存量回流，避免出现负库存）
 * @param batchAutoPicked 未传 batchId，系统按 FIFO 自动挑了批次
 * @param strategy     归属策略（由该批次最近一条定价建议推断，推断不出来为 null，不硬编）
 * @param statUpdated  是否更新了 strategy_daily_stat
 * @param memberId     v2：本笔成交写入的会员 id（未传为 null）
 * @param pushLogId    v2：归因命中的 push_log id（本笔成交算作哪条推送的转化；未命中为 null）
 * @param convertedByPush v2：本笔成交是否构成了"推送后成交"（窗口内同单品）
 * @param attributionNote v2：归因结果的人话说明（为什么不归因也写清楚）
 */
public record SalesFeedbackResponse(
        BigDecimal remainQty,
        String batchStatus,
        Long salesRecordId,
        LocalDate bizDate,
        Long itemId,
        Long batchId,
        String batchNo,
        BigDecimal requestedQty,
        BigDecimal soldQty,
        boolean overSell,
        boolean batchAutoPicked,
        boolean isReturn,
        String strategy,
        boolean statUpdated,
        String message,
        Long memberId,
        Long pushLogId,
        boolean convertedByPush,
        int convertWindowHours,
        String attributionNote,
        /** 本次请求的业务流水号（未传为 null）。 */
        String requestNo,
        /** true = 该流水号已经记过账，本次没有重复扣库存、没有重复写流水与统计，返回的是首次那笔的记录。 */
        boolean duplicate) {
}
