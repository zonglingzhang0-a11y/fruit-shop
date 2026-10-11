package com.fruit.shop.pricing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 店主确认/改价请求（docs/01-技术方案.md 第 5 节接口 6、第 3 节第 7 条：决策必须留痕）。
 *
 * <p>不传 finalPrice 表示"就按建议价卖"；不传 accepted 时，系统按 finalPrice 是否等于建议价自动判定。
 */
@Data
public class DecisionRequest {

    @NotNull(message = "suggestionId 不能为空")
    private Long suggestionId;

    @DecimalMin(value = "0.01", message = "finalPrice 必须大于 0")
    private BigDecimal finalPrice;

    /** 是否采纳建议；不传则 finalPrice == suggestedPrice 视为采纳。 */
    private Boolean accepted;

    /** 操作人，默认"店主"。 */
    @Size(max = 32, message = "operator 最长 32 个字符")
    private String operator;

    /** 决策理由（可选，会原样落库供效果复盘）。 */
    @Size(max = 255, message = "reason 最长 255 个字符")
    private String reason;

    /**
     * 不接受建议时的原因（v2，docs/07 第 1 节 B：落到 {@code pricing_decision.reject_reason}）。
     * 可选：不传时用 {@code reason} 兜底，所以"不接受"必然留下可读原因。
     */
    @Size(max = 64, message = "rejectReason 最长 64 个字符")
    private String rejectReason;
}
