package com.fruit.shop.push.dto;

import java.time.LocalDateTime;

/**
 * push_log 的一行（效果明细，接口返回用）。
 *
 * @param delivered   是否真正送达（= skipReason 为空；只有送达行才计入打开率/转化率的分母）
 * @param skipReason  限频/静默/退订；送达行与群发行为 null
 * @param memberLabel 会员展示名（昵称；群发文案行为"全店群发文案"）
 */
public record PushLogRow(
        Long logId,
        Long taskId,
        Long memberId,
        String memberNo,
        String memberLabel,
        LocalDateTime sentAt,
        boolean delivered,
        boolean opened,
        LocalDateTime openedAt,
        boolean converted,
        LocalDateTime convertedAt,
        Long relatedSaleId,
        Integer convertWindowHours,
        String skipReason) {
}
