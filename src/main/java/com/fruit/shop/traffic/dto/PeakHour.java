package com.fruit.shop.traffic.dto;

/**
 * 高峰小时详情。
 *
 * @param suggestPushHour 建议推送小时 = 高峰小时 - 1（docs/01 第 5 节接口 8；与
 *                        data/seed/calibration/hourly_traffic.json 的 derived_push_hour 口径一致）
 * @param suggestPushTime 形如 "09:00"
 */
public record PeakHour(
        Integer hourOfDay,
        Integer orderCount,
        Integer customerCount,
        Integer suggestPushHour,
        String suggestPushTime) {
}
