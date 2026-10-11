package com.fruit.shop.push.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 推送效果统计响应（接口 {@code GET /api/push/stats}）。
 *
 * <p>分子分母都摆在响应里，避免"口径说不清"：
 * <ul>
 *   <li>{@code delivered} = 送达数 = push_log 中 {@code member_id 非空且 skip_reason 为空} 的行数；</li>
 *   <li>{@code openRate = opened ÷ delivered}（打开率），{@code convertRate = converted ÷ delivered}（推送后成交率）；</li>
 *   <li>{@code skippedLimit / skippedQuiet / skippedOptOut} = 限频/静默/退订拦下的行数，<b>不计入分母</b>；</li>
 *   <li>{@code groupRows} = 群发文案行数（member_id 为空），只代表"全店文案发出去了"，不参与个人指标。</li>
 * </ul>
 */
public record PushStatsResponse(
        LocalDate from,
        LocalDate to,
        Long taskId,
        int convertWindowHours,
        int taskCount,
        int plannedTasks,
        int sentTasks,
        int delivered,
        int opened,
        int converted,
        BigDecimal openRate,
        BigDecimal convertRate,
        int skippedLimit,
        int skippedQuiet,
        int skippedOptOut,
        int groupRows,
        List<PushTaskStat> byTask,
        String note) {
}
