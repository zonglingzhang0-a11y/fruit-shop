package com.fruit.shop.traffic.dto;

/** 每小时客流行（docs/01-技术方案.md 第 5 节接口 7 的元素形状）。 */
public record HourlyRow(
        Integer hourOfDay,
        Integer orderCount,
        Integer customerCount,
        Boolean isPeak) {
}
