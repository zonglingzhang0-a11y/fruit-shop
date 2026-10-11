package com.fruit.shop.pricing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 决策响应。前端主用 {@code decisionId / accepted / finalPrice}，其余为兼容新增字段。
 *
 * @param changed      店主是否改了价（finalPrice != suggestedPrice）
 * @param rejectReason 不接受建议时的原因（v2，落 {@code pricing_decision.reject_reason}；接受时为 null）
 */
public record DecisionResponse(
        Long decisionId,
        Boolean accepted,
        BigDecimal finalPrice,
        BigDecimal suggestedPrice,
        String operator,
        String reason,
        LocalDateTime decidedAt,
        boolean changed,
        String rejectReason) {
}
