package com.fruit.shop.batch.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报废人工登记请求（FR-06：批次的报废由人登记，系统不得自动淘汰）。
 *
 * <p>qty 不传 = 把现存整批报废；传了就是部分报废（例如挑出几个烂果），批次仍在售。
 */
@Data
public class BatchScrapRequest {

    /** 营业日 yyyy-MM-dd：报废计入哪一天的策略日统计。 */
    @NotNull(message = "bizDate 不能为空（yyyy-MM-dd）")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bizDate;

    /** 报废数量；不传则整批报废（= 现存）。 */
    @DecimalMin(value = "0.01", message = "qty 必须大于 0")
    private BigDecimal qty;

    /** 报废原因（必填，留痕用），例如"已过期""腐烂""磕碰"。 */
    @NotBlank(message = "reason 不能为空：报废必须写原因")
    @Size(max = 255, message = "reason 最长 255 个字符")
    private String reason;

    /** 登记人；不传记为"店员"。 */
    @Size(max = 32, message = "operator 最长 32 个字符")
    private String operator;
}
