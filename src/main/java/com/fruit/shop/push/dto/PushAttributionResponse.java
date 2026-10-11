package com.fruit.shop.push.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 归因重跑响应。
 *
 * @param scanned         扫描了多少条销售记录（只算有 member_id、非退货、quantity>0 的）
 * @param newlyAttributed 本次新归因成功的数量（幂等重跑时第二次应为 0）
 * @param alreadyAttributed 之前已经归因过、本次跳过的数量
 * @param unmatched       窗口内没有匹配推送的数量
 * @param windowHours     归因窗口（3 小时）
 * @param convertedLogIds 本次新归因的 push_log id
 */
public record PushAttributionResponse(
        LocalDate from,
        LocalDate to,
        Long memberId,
        int scanned,
        int newlyAttributed,
        int alreadyAttributed,
        int unmatched,
        int windowHours,
        List<Long> convertedLogIds,
        String note) {
}
