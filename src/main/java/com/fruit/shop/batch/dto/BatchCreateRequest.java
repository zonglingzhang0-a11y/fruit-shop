package com.fruit.shop.batch.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 登记到货批次请求（docs/01-技术方案.md 第 5 节接口 2）。
 *
 * <p>只有 itemId/arriveDate/qty 是必须的：
 * shelfLifeDays 不传就用商品档案里的保质期，costPrice 不传就用商品档案里的参考成本价——
 * 街边店主打单时常记不住这两个数，能省一步就省一步。
 */
@Data
public class BatchCreateRequest {

    @NotNull(message = "itemId 不能为空")
    private Long itemId;

    /** 到货日 yyyy-MM-dd。 */
    @NotNull(message = "arriveDate 不能为空（yyyy-MM-dd）")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate arriveDate;

    /** 保质期天数；不传则取商品档案 shelf_life_days。 */
    @Min(value = 0, message = "shelfLifeDays 不能为负数")
    private Integer shelfLifeDays;

    /** 到货量，必须大于 0。 */
    @NotNull(message = "qty 不能为空")
    @DecimalMin(value = "0.01", message = "qty 必须大于 0")
    private BigDecimal qty;

    /** 该批次实际进货价；不传则取商品档案 cost_price。 */
    @DecimalMin(value = "0.00", message = "costPrice 不能为负数")
    private BigDecimal costPrice;

    /** 可选：店主自定义批次号；不传则按 到货日+商品 自动生成。 */
    private String batchNo;
}
