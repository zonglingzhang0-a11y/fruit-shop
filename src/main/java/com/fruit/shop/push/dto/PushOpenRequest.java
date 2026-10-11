package com.fruit.shop.push.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标记推送已打开请求（对应参考架构 push_log.opened/opened_at；打开率 = 打开数 ÷ 送达数）。
 *
 * <p>两种定位方式，二选一：
 * <ul>
 *   <li>{@code logId}：直接指定 push_log 行（send 响应里的 messageIds / /api/push/tasks 明细里都有）；</li>
 *   <li>{@code taskId + memberId}：按"哪个计划发给了谁"定位那一行。</li>
 * </ul>
 * 幂等：已打开的行重复标记不会覆盖首次 opened_at。
 */
@Data
public class PushOpenRequest {

    private Long logId;

    private Long taskId;

    private Long memberId;

    /** 可选：模拟打开时刻，默认系统当前时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime openedAt;
}
