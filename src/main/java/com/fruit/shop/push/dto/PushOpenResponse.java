package com.fruit.shop.push.dto;

import java.time.LocalDateTime;

/**
 * 标记打开响应。
 *
 * @param alreadyOpened true = 这行之前就标记过打开（幂等，openedAt 仍是首次打开时间）
 */
public record PushOpenResponse(
        Long logId,
        Long taskId,
        Long memberId,
        boolean opened,
        LocalDateTime openedAt,
        boolean alreadyOpened,
        boolean converted,
        Long relatedSaleId,
        String note) {
}
