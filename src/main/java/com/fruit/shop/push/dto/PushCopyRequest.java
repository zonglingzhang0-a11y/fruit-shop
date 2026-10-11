package com.fruit.shop.push.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 文案生成输入：字段刻意与界面/接口解耦，方便将来换成大模型实现时直接拼 Prompt。
 *
 * @param nickname  会员昵称；为 null 表示全店群发文案（push_log.member_id 为 NULL）
 * @param timeHint  建议到店时段的人话描述，可为 null
 * @param channel   IN_APP（站内个人消息）或 GROUP_TEXT（可转发到顾客群的文案）
 */
public record PushCopyRequest(
        String storeName,
        String itemName,
        String categoryName,
        String unit,
        BigDecimal originalPrice,
        BigDecimal discountPrice,
        BigDecimal discountRate,
        String discountLabel,
        Integer daysLeft,
        LocalDate expireDate,
        BigDecimal remainQty,
        String nickname,
        String timeHint,
        String channel) {
}
