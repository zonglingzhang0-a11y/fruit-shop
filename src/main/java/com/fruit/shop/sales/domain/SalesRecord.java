package com.fruit.shop.sales.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 销售回流记录。source=SIM 表示来自仿真数据导入，REAL 表示真实门店。 */
@Data
@TableName("sales_record")
public class SalesRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate bizDate;

    private LocalDateTime saleTime;

    private Long itemId;

    private Long batchId;

    private BigDecimal quantity;

    private BigDecimal unitPrice;

    private Boolean isDiscount;

    private Boolean isReturn;

    /** v2：成交会员 id（仅存 id，不含手机号）；为空表示未识别会员，该笔不参与推送转化归因。 */
    private Long memberId;

    private String source;

    /** 业务流水号（幂等键，可空；唯一索引 uk_sales_request_no）。 */
    private String requestNo;

    /**
     * 请求数量（带符号，退货为负；可空，种子数据为空）。库存不足时 quantity 会被截断成实扣量，
     * 这里保留原始请求量：重复提交据此判断"是不是同一笔"，并如实回放 overSell。
     */
    private BigDecimal requestedQty;
}
