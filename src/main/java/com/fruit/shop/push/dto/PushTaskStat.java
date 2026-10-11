package com.fruit.shop.push.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 按任务的推送效果明细（docs/07 第 3 节验收 4：打开率、推送后成交，分子分母都要能说明）。
 *
 * <p>口径：
 * <ul>
 *   <li>{@code delivered} = 该任务 push_log 里 {@code member_id 非空且 skip_reason 为空} 的行数（送达数，分母）；</li>
 *   <li>{@code openRate = opened ÷ delivered}；{@code convertRate = converted ÷ delivered}；</li>
 *   <li>{@code skippedLimit / skippedQuiet / skippedOptOut} = 被限频/静默/退订拦下的行数（不计入分母）；</li>
 *   <li>{@code groupRows} = 群发文案行数（member_id 为空），不参与个人打开率/转化率。</li>
 * </ul>
 *
 * @param logs 仅"按 taskId 查明细"时填充，列表模式下为空数组
 */
public record PushTaskStat(
        Long taskId,
        LocalDate bizDate,
        LocalDateTime planTime,
        LocalDateTime sentAt,
        String status,
        String channel,
        Long relatedItemId,
        String itemName,
        Long relatedSuggestionId,
        Integer targetCount,
        String title,
        String reason,
        String audienceRule,
        int delivered,
        int opened,
        int converted,
        int skippedLimit,
        int skippedQuiet,
        int skippedOptOut,
        int groupRows,
        BigDecimal openRate,
        BigDecimal convertRate,
        List<PushLogRow> logs) {
}
