package com.fruit.shop.traffic.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 高峰时段响应（docs/01-技术方案.md 第 5 节接口 8）。
 *
 * <p>主字段：{@code date / peakHours / suggestPushHours / note}；
 * 其余为兼容新增：{@code derived} 表示高峰是算出来的还是表里标好的，
 * {@code peakHourDetails} 带每小时的成交笔数，前端做图表时不用再请求一次。
 */
public record PeakResponse(
        LocalDate date,
        List<Integer> peakHours,
        List<Integer> suggestPushHours,
        String note,
        Boolean dataAvailable,
        Boolean derived,
        String derivationRule,
        Integer maxOrderCount,
        Double averageOrderCount,
        List<String> suggestPushTimes,
        List<PeakHour> peakHourDetails) {
}
