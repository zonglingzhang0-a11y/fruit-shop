package com.fruit.shop.push.dto;

import java.time.LocalDateTime;

/**
 * 实际发送出去的一条记录。
 *
 * <p>v2 拆表后 {@code logId} 是 {@code push_log.id}（承接 v1 的 messageId 语义）：
 * 送达的日志行 {@code skip_reason} 为空，打开（opened）与转化（converted）都记在这一行上。
 */
public record PushSentItem(
        Long logId,
        Long taskId,
        Long memberId,
        String nickname,
        String channel,
        String title,
        String content,
        LocalDateTime sendAt) {
}
