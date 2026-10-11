package com.fruit.shop.sales.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售回流请求（docs/01-技术方案.md 第 5 节接口 12）。
 *
 * <p>支持两种用法：传 {@code batchId} 精确回流到批次；只传 {@code itemId} 时按"最早到期的在售批次"出库（FIFO）。
 * {@code quantity} 允许为负或带 {@code isReturn=true}：表示退货，剩余量会加回去。
 */
@Data
public class SalesFeedbackRequest {

    @NotNull(message = "bizDate 不能为空（yyyy-MM-dd）")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bizDate;

    /** 商品 id；与 batchId 至少传一个。 */
    private Long itemId;

    /** 批次 id；不传则按 FIFO 自动挑该商品最早到期的在售批次。 */
    private Long batchId;

    /** 销售数量；负数表示退货。 */
    @NotNull(message = "quantity 不能为空")
    private BigDecimal quantity;

    /** 成交单价（元/单位）。 */
    @NotNull(message = "unitPrice 不能为空")
    @DecimalMin(value = "0.00", message = "unitPrice 不能为负数")
    private BigDecimal unitPrice;

    /** 成交时间；不传用当前时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime saleTime;

    /** 是否打折成交；不传则按 unitPrice 是否低于原价自动判断。 */
    private Boolean isDiscount;

    /** 是否退货；不传则按 quantity < 0 自动判断。 */
    private Boolean isReturn;

    /** REAL / SIM，默认 REAL（仿真数据回流标 SIM）。 */
    private String source;

    /**
     * v2 可选：成交会员 id（push_log → sales_record 归因用）。
     * 传了就会写进 {@code sales_record.member_id}，并在写入后自动触发一次 3 小时同单品归因；
     * 不传表示这笔没识别到会员，不参与推送转化统计。
     */
    private Long memberId;
}
