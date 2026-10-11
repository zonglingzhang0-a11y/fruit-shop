package com.fruit.shop.traffic;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.traffic.domain.TrafficHourly;
import com.fruit.shop.traffic.dto.HourlyRow;
import com.fruit.shop.traffic.dto.PeakHour;
import com.fruit.shop.traffic.dto.PeakResponse;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时段客流与高峰识别（docs/01-技术方案.md 第 5 节接口 7、8）。
 *
 * <p>数据口径：{@code shop_traffic_hourly} 的 order_count 用历史成交笔数代替真实人流（不引入摄像头/传感器）。
 * 高峰判定优先用表里的 is_peak 标志；导入数据没标时按"成交笔数 >= 当日最高笔数 × 0.8"自动判定，
 * 并在响应里用 {@code derived=true} 与 {@code derivationRule} 明确告知，避免把算法口径伪装成实测口径。
 *
 * <p>建议推送时间 = 高峰小时 - 1（高峰前 1 小时），与 calibration 产物的 derived_push_hour 一致。
 */
@Service
public class TrafficService {

    private static final Logger log = LoggerFactory.getLogger(TrafficService.class);

    /** 自动判定高峰的相对阈值：当日最高成交笔数的 80%。 */
    public static final double PEAK_RATIO = 0.8;

    private static final String DERIVATION_RULE = "orderCount >= 当日最高成交笔数 × 0.8";

    private final TrafficHourlyMapper trafficMapper;

    public TrafficService(TrafficHourlyMapper trafficMapper) {
        this.trafficMapper = trafficMapper;
    }

    /** 7. 按日查每小时客流；当天无记录返回空数组。有记录时补全 0~23 点，方便前端直接画图。 */
    public List<HourlyRow> hourly(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<TrafficHourly> rows = rowsOf(day);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Integer, TrafficHourly> byHour = new LinkedHashMap<>();
        for (TrafficHourly row : rows) {
            if (row.getHourOfDay() != null) {
                byHour.put(row.getHourOfDay(), row);
            }
        }
        List<HourlyRow> result = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            TrafficHourly row = byHour.get(hour);
            result.add(new HourlyRow(
                    hour,
                    row == null || row.getOrderCount() == null ? 0 : row.getOrderCount(),
                    row == null || row.getCustomerCount() == null ? 0 : row.getCustomerCount(),
                    row != null && Boolean.TRUE.equals(row.getIsPeak())));
        }
        return result;
    }

    /** 8. 高峰时段 + 高峰前 1 小时的推送建议。 */
    public PeakResponse peaks(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<TrafficHourly> rows = rowsOf(day);
        if (rows.isEmpty()) {
            return new PeakResponse(day, List.of(), List.of(),
                    "当日（" + day + "）暂无客流数据，无法给出高峰与推送建议；系统保持空闲，不会自动发消息。",
                    false, false, null, null, null, List.of(), List.of());
        }

        List<TrafficHourly> peakRows = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.getIsPeak()))
                .sorted(Comparator.comparing(TrafficHourly::getHourOfDay))
                .toList();
        boolean derived = peakRows.isEmpty();
        int maxOrder = rows.stream().mapToInt(row -> row.getOrderCount() == null ? 0 : row.getOrderCount()).max().orElse(0);
        if (derived) {
            peakRows = rows.stream()
                    .filter(row -> isPeak(row.getOrderCount(), maxOrder))
                    .sorted(Comparator.comparing(TrafficHourly::getHourOfDay))
                    .toList();
        }

        double average = rows.stream().mapToInt(row -> row.getOrderCount() == null ? 0 : row.getOrderCount()).average().orElse(0);
        if (peakRows.isEmpty()) {
            return new PeakResponse(day, List.of(), List.of(),
                    "当日（" + day + "）没有成交记录（或全部为 0），暂不推送，避免打扰顾客。",
                    true, derived, derived ? DERIVATION_RULE : null, maxOrder, average, List.of(), List.of());
        }

        List<Integer> peakHours = new ArrayList<>();
        List<Integer> pushHours = new ArrayList<>();
        List<String> pushTimes = new ArrayList<>();
        List<PeakHour> details = new ArrayList<>();
        for (TrafficHourly row : peakRows) {
            int hour = row.getHourOfDay();
            int pushHour = (hour - 1 + 24) % 24;
            String pushTime = String.format("%02d:00", pushHour);
            peakHours.add(hour);
            if (!pushHours.contains(pushHour)) {
                pushHours.add(pushHour);
                pushTimes.add(pushTime);
            }
            details.add(new PeakHour(hour, row.getOrderCount(), row.getCustomerCount(), pushHour, pushTime));
        }
        pushHours.sort(Integer::compareTo);
        pushTimes.sort(String::compareTo);

        StringBuilder note = new StringBuilder();
        note.append("当日高峰时段：").append(joinHours(peakHours))
                .append("；建议在高峰前 1 小时（").append(String.join("、", pushTimes))
                .append("）推送，顾客正好在到店路上。");
        if (derived) {
            note.append("（表里没有 is_peak 标志，已按\"").append(DERIVATION_RULE).append("\"自动判定）");
        }
        log.debug("[traffic] {} 高峰 {} → 建议推送 {}", day, peakHours, pushTimes);
        return new PeakResponse(day, peakHours, pushHours, note.toString(), true, derived,
                derived ? DERIVATION_RULE : null, maxOrder, average, pushTimes, details);
    }

    private List<TrafficHourly> rowsOf(LocalDate day) {
        return trafficMapper.selectList(Wrappers.<TrafficHourly>lambdaQuery()
                .eq(TrafficHourly::getStatDate, day)
                .orderByAsc(TrafficHourly::getHourOfDay));
    }

    /**
     * 高峰判定的唯一实现（`/api/traffic/peaks` 与周内规律 {@code TrafficProfileService} 共用，
     * 不允许各写一套阈值）：{@code value >= max × 0.8}。
     * {@code max <= 0}（当天/该星期完全没有成交）时不算高峰。
     */
    public static boolean isPeak(Number value, Number max) {
        if (value == null || max == null) {
            return false;
        }
        return isPeak(BigDecimal.valueOf(value.doubleValue()), BigDecimal.valueOf(max.doubleValue()));
    }

    /** 高峰判定的 BigDecimal 版本（周内规律里是小数均值）。 */
    public static boolean isPeak(BigDecimal value, BigDecimal max) {
        if (value == null || max == null || max.signum() <= 0) {
            return false;
        }
        return value.compareTo(max.multiply(BigDecimal.valueOf(PEAK_RATIO))) >= 0;
    }

    private static String joinHours(List<Integer> hours) {
        List<String> parts = new ArrayList<>(hours.size());
        for (Integer hour : hours) {
            parts.add(String.format("%02d:00-%02d:00", hour, (hour + 1) % 24));
        }
        return String.join("、", parts);
    }
}
