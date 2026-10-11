package com.fruit.shop.push.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 推送文案预览响应（docs/01-技术方案.md 第 5 节接口 9）。
 *
 * <p>主字段：{@code title / content / targetCount / targets}——title/content 是可直接发到顾客群的群发文案，
 * targets 里的每条带各自的昵称版本文案，店主可以逐条看。
 *
 * <p>v2 拆表（docs/07 第 1 节 C）：<b>预览 = 建计划</b>，因此额外返回计划详情
 * {@code taskId / planTime / reason / audienceRule / planStatus / candidateMemberIds}，
 * 店主确认后把这个 {@code taskId} 传给 {@code POST /api/push/send} 即完成"先预览、再发送"两步操作。
 * {@code messageIds} 为 v1 兼容字段：拆表后返回 {@code [taskId]}（旧前端把它当 messageIds 传回 send 仍能发送该计划）。
 */
public record PushPreviewResponse(
        String title,
        String content,
        int targetCount,
        List<PushTarget> targets,
        Long itemId,
        String itemName,
        String categoryName,
        String unit,
        BigDecimal basePrice,
        BigDecimal discountRate,
        BigDecimal discountPrice,
        String discountLabel,
        Integer daysLeft,
        LocalDate expireDate,
        String batchNo,
        LocalDate bizDate,
        String storeName,
        Integer suggestPushHour,
        String suggestPushTime,
        List<Integer> peakHours,
        String sendTimeHint,
        int candidateCount,
        int sendableCount,
        int groupOnlyMemberCount,
        String copyWriter,
        List<PushSkipItem> skipped,
        List<Long> messageIds,
        // ---------------- v2 计划字段（docs/07 第 1 节 C）----------------
        /** 新建的 push_task.id（status=PLANNED，只建计划不发送）。 */
        Long taskId,
        /** 计划发送时间 = 当天高峰小时前 30~60 分钟（沿用"高峰前 1 小时"口径）。 */
        LocalDateTime planTime,
        /** 为什么这个时间、为什么这些人（中文人话）。 */
        String reason,
        /** 选人规则文本（docs/01 规则 6），发送时据此复算名单。 */
        String audienceRule,
        /** 计划状态：PLANNED / SENT / CANCELLED。 */
        String planStatus,
        /** 计划关联的定价建议 id（可空；决策留痕链条：建议 → 计划 → 日志 → 成交）。 */
        Long relatedSuggestionId,
        /** 计划 audience 的会员 id（按偏好分降序；发送时按同一规则复算）。 */
        List<Long> candidateMemberIds,
        /** 计划说明：本条预览写了什么、没写什么（合规口径，避免"预览偷偷发消息"）。 */
        String planNote) {
}
