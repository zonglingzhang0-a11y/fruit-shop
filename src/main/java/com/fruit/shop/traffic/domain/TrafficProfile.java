package com.fruit.shop.traffic.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 周内客流规律（docs/07 第 1 节 D，对应参考架构 traffic_profile）：按"星期 × 小时"聚合的历史规律。
 *
 * <p>口径要点：{@code avg_order_count} 是**按样本天数平均**（该单元格有记录的天数），
 * 不是按日历天数——否则闭店的时段会被按 0 拉低；{@code sample_days} 一并落库，让"规律有多可靠"可核查。
 * {@code is_peak} 沿用 {@code TrafficService} 的高峰判定（≥ 该星期内最高均值 × 0.8）。
 */
@Data
@TableName("shop_traffic_profile")
public class TrafficProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 1=周一 … 7=周日（{@code java.time.DayOfWeek.getValue()}）。 */
    private Integer weekday;

    /** 0~23。 */
    private Integer hourOfDay;

    /** 该（星期, 小时）的平均成交笔数 = Σorder_count ÷ sample_days。 */
    private BigDecimal avgOrderCount;

    /** 该（星期, 小时）的平均去重客数。 */
    private BigDecimal avgCustomerCount;

    /** 参与平均的样本天数（该单元格有客流记录的天数）。 */
    private Integer sampleDays;

    /** 是否高峰：avg_order_count ≥ 该星期内最高 avg_order_count × 0.8。 */
    private Boolean isPeak;

    private LocalDateTime computedAt;
}
