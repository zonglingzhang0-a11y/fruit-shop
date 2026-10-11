package com.fruit.shop.push.dto;

/**
 * 文案产物。群发文案落 {@code push_task.title}（VARCHAR(64)）/ {@code push_task.content}（VARCHAR(512)）；
 * 个人版文案按昵称在发送时现拼，只出现在发送响应里（push_log 不存文案）。
 */
public record PushCopy(String title, String content) {
}
