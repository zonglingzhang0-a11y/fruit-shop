package com.fruit.shop.traffic.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 周内客流规律响应（"重算"与"查询"两条路径共用同一形状，docs/07 第 1 节 D）。
 *
 * @param computedAt 最近一次重算时间（查询时取行里的最大值；没有数据为 null）
 * @param sampleDays 参与聚合的日历天数（重算时=窗口内出现过的日期数；查询时=行里最大样本天数）
 * @param rowCount   规律行数（星期 × 小时，最多 7×24）
 * @param note       人话说明（口径 + 高峰；无数据时明确提示"先重算"）
 * @param profiles   规律明细，按 weekday、hourOfDay 升序
 */
public record TrafficProfileResponse(
        LocalDateTime computedAt,
        int sampleDays,
        int rowCount,
        String note,
        List<TrafficProfileRow> profiles) {
}
