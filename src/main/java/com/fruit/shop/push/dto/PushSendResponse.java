package com.fruit.shop.push.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发送响应（docs/01-技术方案.md 第 5 节接口 10：返回实际发送与跳过条数）。
 *
 * <p>主字段：{@code sent / skippedByLimit / skippedByQuietHours / messageIds}；
 * 其余为兼容新增：{@code sentPersonal}（个人推送）、{@code sentGroup}（群发文案）、
 * {@code skippedByOther}（会员不存在等），以及逐条明细。
 *
 * <p>v2 拆表后的口径补充：
 * <ul>
 *   <li>{@code messageIds} / {@code groupMessageId} 现在是 {@code push_log.id}（承接 v1 的 messageId 语义）；</li>
 *   <li>{@code taskId / taskStatus / planTime / alreadySent} 指向本次发送的计划：有 ≥1 条送达才置 SENT 并写 sent_at，
 *       全部被拦下则保持 PLANNED 可重发；对已 SENT 的计划重复调用幂等（不再写日志，{@code alreadySent=true}）；</li>
 *   <li>{@code skippedByUnsubscribed} = 退订（会员关闭推送开关）被拦下的条数，
 *       与 {@code skippedByLimit}/{@code skippedByQuietHours} 一样都写进了 push_log.skip_reason；</li>
 *   <li>{@code note} 用一句中文说明本次到底发了什么、为什么有些没发（合规与答辩口径）。</li>
 * </ul>
 */
public record PushSendResponse(
        int sent,
        int skippedByLimit,
        int skippedByQuietHours,
        List<Long> messageIds,
        LocalDate bizDate,
        LocalDateTime sendAt,
        int sentPersonal,
        int sentGroup,
        int skippedByOther,
        Long groupMessageId,
        boolean quietNow,
        String quietHours,
        int dailyLimitPerMember,
        List<PushSentItem> sentItems,
        List<PushSkipItem> skipped,
        // ---------------- v2 计划与归因字段 ----------------
        /** 本次发送的计划 id（现拼模式 = 隐式新建的计划）。 */
        Long taskId,
        /** 本次涉及的全部计划 id。 */
        List<Long> taskIds,
        /** 计划状态：SENT（≥1 条送达）/ PLANNED（0 条送达，保留可重发）/ 其它。 */
        String taskStatus,
        /** 计划发送时间（高峰前 1 小时口径）。 */
        LocalDateTime planTime,
        /** 重复发送已 SENT 的计划 → true（幂等，不再写 push_log）。 */
        boolean alreadySent,
        /** 退订被拦下的条数（skip_reason=退订）。 */
        int skippedByUnsubscribed,
        /** 归因窗口小时数（默认 3，落 push_log.convert_window_hours）。 */
        int convertWindowHours,
        /** 人话说明：本次发送结果与口径。 */
        String note) {
}
