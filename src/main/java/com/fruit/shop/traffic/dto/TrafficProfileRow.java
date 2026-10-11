package com.fruit.shop.traffic.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 周内客流规律的一行（docs/07 第 1 节 D）：某个星期几的某个小时。
 *
 * @param weekdayLabel      "周一"~"周日"（前端直接展示，省得自己算）
 * @param avgOrderCount     按样本天数平均的成交笔数
 * @param avgCustomerCount  按样本天数平均的去重客数
 * @param sampleDays        参与平均的样本天数
 * @param isPeak            是否高峰（≥ 该星期内最高均值 × 0.8，与 /api/traffic/peaks 同口径）
 * @param computedAt        该行最近一次重算时间
 */
public record TrafficProfileRow(
        Integer weekday,
        String weekdayLabel,
        Integer hourOfDay,
        BigDecimal avgOrderCount,
        BigDecimal avgCustomerCount,
        Integer sampleDays,
        Boolean isPeak,
        LocalDateTime computedAt) {
}
