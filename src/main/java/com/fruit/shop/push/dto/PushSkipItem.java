package com.fruit.shop.push.dto;

/**
 * 被跳过的对象（人话原因 + 机器可读的归类）。
 *
 * @param bucket     LIMIT=每日上限 / QUIET=静默时段 / OPT_OUT=退订（关闭推送开关） / GROUP_ONLY=无偏好只群发 / OTHER=其它（会员不存在等）
 * @param skipReason v2：与 {@code push_log.skip_reason} 同一套枚举（限频/静默/退订）；
 *                   只有"计划 audience 内的会员被拦下"才有值，选人阶段就被排除的对象为 null
 */
public record PushSkipItem(
        Long memberId,
        String nickname,
        String memberNo,
        String reason,
        String bucket,
        String skipReason) {
}
