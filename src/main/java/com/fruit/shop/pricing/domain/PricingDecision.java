package com.fruit.shop.pricing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 定价决策：记录店主是否采纳建议、改成了什么价，这是"人机协同"与效果对比的证据。 */
@Data
@TableName("pricing_decision")
public class PricingDecision {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long suggestionId;

    private BigDecimal suggestedPrice;

    private BigDecimal finalPrice;

    /** 是否采纳建议 */
    private Boolean accepted;

    private String operator;

    private String reason;

    /** v2：店主不接受建议时的原因枚举（自由文本也兼容；接受时为 null）。 */
    private String rejectReason;

    private LocalDateTime decidedAt;
}
