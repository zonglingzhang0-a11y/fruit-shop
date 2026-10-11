package com.fruit.shop.sales.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 策略日统计：效果看板直接查这张表，避免前端做重计算。 */
@Data
@TableName("strategy_daily_stat")
public class StrategyDailyStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate bizDate;

    /** FIXED / RULE / MODEL */
    private String strategy;

    private BigDecimal soldQty;

    private BigDecimal scrapQty;

    private BigDecimal revenue;

    private BigDecimal cost;

    private BigDecimal grossProfit;

    /** 损耗率 = 报废量 / 进货量 */
    private BigDecimal scrapRate;

    private BigDecimal stockoutRate;
}
