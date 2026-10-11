package com.fruit.shop.push.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 推送文案预览请求（docs/01-技术方案.md 第 5 节接口 9）。 */
@Data
public class PushPreviewRequest {

    @NotNull(message = "itemId 不能为空")
    private Long itemId;

    /** 折扣系数；不传则按该商品最近批次剩余天数用 RULE 档位推算。 */
    private BigDecimal discountRate;

    /** 想要预览的会员条数，默认 10，上限 200。 */
    @Min(value = 1, message = "targetCount 至少为 1")
    @Max(value = 200, message = "targetCount 最多 200")
    private Integer targetCount;

    /** 业务日，默认今天（用于算剩余天数与客流高峰）。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bizDate;

    /** 可选：模拟"当前时刻"，用于演示不同时段的静默/可发状态；不传用系统时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sendAt;

    /**
     * 可选（默认 false，纯预览不写库）：
     * true 时把每个推荐对象的文案落成 {@code channel=IN_APP_DRAFT} 的草稿（草稿不算已发送、不占每日限额），
     * 返回的 {@code messageIds} 可直接传给 {@code POST /api/push/send} 完成"先预览、再发送"的两步操作。
     */
    private Boolean persistDraft;
}
