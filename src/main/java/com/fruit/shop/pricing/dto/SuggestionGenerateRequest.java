package com.fruit.shop.pricing.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

/** 生成定价建议请求（docs/01-技术方案.md 第 5 节接口 4）。 */
@Data
public class SuggestionGenerateRequest {

    /** 业务日 yyyy-MM-dd；不传默认今天。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bizDate;

    /** FIXED / RULE / MODEL；不传用 app.pricing.active-strategy。 */
    private String strategy;

    /** 可选：本次生成覆盖的临期天数阈值，不传用 app.pricing.near-expiry-days。 */
    private Integer days;
}
