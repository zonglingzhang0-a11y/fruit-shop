package com.fruit.shop.item.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 新增/更新商品档案请求（契约外补充接口：没有商品档案就没法登记批次）。 */
@Data
public class ItemCreateRequest {

    @NotBlank(message = "itemCode 不能为空")
    @Size(max = 32, message = "itemCode 最长 32 个字符")
    private String itemCode;

    @NotBlank(message = "itemName 不能为空")
    @Size(max = 64, message = "itemName 最长 64 个字符")
    private String itemName;

    @NotBlank(message = "categoryName 不能为空（推送选人要靠品类偏好）")
    @Size(max = 32, message = "categoryName 最长 32 个字符")
    private String categoryName;

    /** 计量单位：份/斤/盒，默认"份"。 */
    @Size(max = 16, message = "unit 最长 16 个字符")
    private String unit;

    @NotNull(message = "costPrice 不能为空")
    @DecimalMin(value = "0.00", message = "costPrice 不能为负数")
    private BigDecimal costPrice;

    @NotNull(message = "basePrice 不能为空")
    @DecimalMin(value = "0.01", message = "basePrice 必须大于 0")
    private BigDecimal basePrice;

    @NotNull(message = "shelfLifeDays 不能为空")
    @Min(value = 0, message = "shelfLifeDays 不能为负数")
    private Integer shelfLifeDays;

    @DecimalMin(value = "0.00", message = "lossRatePct 不能为负数")
    private BigDecimal lossRatePct;

    /** ON/OFF，默认 ON。 */
    private String status;
}
