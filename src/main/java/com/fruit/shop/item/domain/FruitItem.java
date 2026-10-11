package com.fruit.shop.item.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 单品（水果 SKU）。 */
@Data
@TableName("fruit_item")
public class FruitItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 编码/条码 */
    private String itemCode;

    private String itemName;

    /** 品类名，如 浆果类、柑橘类 */
    private String categoryName;

    /** 计量单位：份/斤/盒 */
    private String unit;

    /** 参考成本价（元/单位） */
    private BigDecimal costPrice;

    /** 原价（元/单位） */
    private BigDecimal basePrice;

    /** 保质期天数 */
    private Integer shelfLifeDays;

    /** 参考损耗率% */
    private BigDecimal lossRatePct;

    /** ON/OFF */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
