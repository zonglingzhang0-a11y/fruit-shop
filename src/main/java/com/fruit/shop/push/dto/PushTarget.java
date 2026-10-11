package com.fruit.shop.push.dto;

import java.math.BigDecimal;

/**
 * 预览里的推荐对象（docs/01-技术方案.md 第 5 节接口 9 的元素形状：memberId/nickname/categoryName/reason）。
 *
 * @param hitPref    是否命中该品类偏好（true=买过该品类，false=只参与全店群发文案）
 * @param todayPushed 今日已推送条数（用于向店主解释为什么跳过）
 * @param quotaLeft  今日还可推送条数
 * @param sendable   当前时刻是否可以发送（受推送开关、每日上限、静默时段约束）
 * @param skipReason sendable=false 时的人话原因，否则为 null
 * @param skipCode   v2：机器可读跳过码（限频/静默/退订），与 {@code push_log.skip_reason} 同一套枚举；否则为 null
 */
public record PushTarget(
        Long memberId,
        String memberNo,
        String nickname,
        String phoneTail,
        String categoryName,
        BigDecimal prefScore,
        Integer buyCount,
        String reason,
        Boolean hitPref,
        Integer todayPushed,
        Integer quotaLeft,
        Boolean sendable,
        String skipReason,
        String title,
        String content,
        String skipCode) {
}
