package com.fruit.shop.traffic.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 时段客流。刻意不引入摄像头/传感器：用历史成交笔数代替真实人流，
 * 这也是"动态感知"在课程条件下站得住的做法。
 */
@Data
@TableName("shop_traffic_hourly")
public class TrafficHourly {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;

    /** 0~23 */
    private Integer hourOfDay;

    private Integer orderCount;

    private Integer customerCount;

    private Boolean isPeak;
}
