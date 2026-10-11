package com.fruit.shop.batch.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一轮只读临期扫描的结果（给日志、测试与后续接口复用）。
 *
 * <p>刻意只报告"算了什么、有几个有风险"，不含任何决策/推送结果：
 * 扫描器不生成定价建议、不写 {@code pricing_decision}、不发消息（docs/07 第 2 节）。
 *
 * @param scanned      本轮扫描到的临期批次（ON_SALE 且 remain_qty &gt; 0 且 days_left ≤ 阈值）
 * @param updated      成功写入派生指标的批次数
 * @param atRisk       其中判为"有风险"（预计可售天数 &gt; 剩余天数）的批次数
 * @param riskyBatchIds   有风险批次 id（最多 {@link #MAX_REPORTED} 个，便于测试与排查）
 * @param riskyBatchNos   有风险批次号（同上）
 * @param metricsUpdatedAt 本轮派生指标的写入时间（{@code metrics_updated_at}）
 */
public record ScanResult(
        LocalDate bizDate,
        int threshold,
        int scanned,
        int updated,
        int atRisk,
        List<Long> riskyBatchIds,
        List<String> riskyBatchNos,
        LocalDateTime metricsUpdatedAt,
        String message) {

    /** 结果里最多回报多少个有风险批次（避免日志/响应被 2000 个批次淹没）。 */
    public static final int MAX_REPORTED = 20;
}
