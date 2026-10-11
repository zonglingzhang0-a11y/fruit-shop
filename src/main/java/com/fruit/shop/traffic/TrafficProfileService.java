package com.fruit.shop.traffic;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.traffic.domain.TrafficHourly;
import com.fruit.shop.traffic.domain.TrafficProfile;
import com.fruit.shop.traffic.dto.TrafficProfileResponse;
import com.fruit.shop.traffic.dto.TrafficProfileRow;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import com.fruit.shop.traffic.mapper.TrafficProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 周内客流规律（docs/07 第 1 节 D）：把 {@code shop_traffic_hourly} 聚合成"星期 × 小时"的规律，
 * 结果落 {@code shop_traffic_profile}，供 H5 特价页与工作台直接查询。
 *
 * <p><b>聚合口径</b>：
 * <ul>
 *   <li>{@code avg_order_count} = 该（星期, 小时）的 Σorder_count ÷ {@code sample_days}，
 *       {@code sample_days} = 该单元格**有客流记录的天数**（不是该星期在日历里出现的次数）——
 *       闭店/没有记录的时段不会把工作日拉低；</li>
 *   <li>{@code is_peak} = {@code avg_order_count ≥ 该星期内最高 avg_order_count × 0.8}，
 *       直接复用 {@link TrafficService#isPeak(BigDecimal, BigDecimal)}，不另立标准；</li>
 *   <li>重算是**显式调用**才发生的写操作（{@code POST /api/traffic/profile/recompute}），
 *       属于"派生数据重算"，不改任何业务状态（docs/07 第 2 节允许）。</li>
 * </ul>
 */
@Service
public class TrafficProfileService {

    private static final Logger log = LoggerFactory.getLogger(TrafficProfileService.class);

    /** 均值保留 3 位小数（表字段 DECIMAL(10,3)）。 */
    private static final int AVG_SCALE = 3;

    private static final String[] WEEKDAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final TrafficHourlyMapper hourlyMapper;
    private final TrafficProfileMapper profileMapper;

    public TrafficProfileService(TrafficHourlyMapper hourlyMapper, TrafficProfileMapper profileMapper) {
        this.hourlyMapper = hourlyMapper;
        this.profileMapper = profileMapper;
    }

    /** 重算（全量）：以 shop_traffic_hourly 全表为样本。 */
    @Transactional
    public TrafficProfileResponse recompute() {
        return recompute(null, null);
    }

    /**
     * 重算（可限定样本窗口）：只用 {@code [from, to]} 内的客流记录重新聚合。
     *
     * <p>无论是否限定窗口，都是**全表重建** shop_traffic_profile（先清空再写入），
     * 保证 (星期, 小时) 唯一、不会留下上次重算的陈旧单元格。
     *
     * @param from 样本起始日，null 表示不限制
     * @param to   样本结束日，null 表示不限制
     */
    @Transactional
    public TrafficProfileResponse recompute(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.badRequest("样本窗口不合法：from(" + from + ") 不能晚于 to(" + to + ")");
        }
        List<TrafficHourly> rows = hourlyMapper.selectList(Wrappers.<TrafficHourly>lambdaQuery()
                .ge(from != null, TrafficHourly::getStatDate, from)
                .le(to != null, TrafficHourly::getStatDate, to));

        Map<Integer, Map<Integer, List<TrafficHourly>>> grouped = new TreeMap<>();
        Set<LocalDate> days = new TreeSet<>();
        for (TrafficHourly row : rows) {
            if (row.getStatDate() == null || row.getHourOfDay() == null
                    || row.getHourOfDay() < 0 || row.getHourOfDay() > 23) {
                continue;
            }
            int weekday = row.getStatDate().getDayOfWeek().getValue();
            grouped.computeIfAbsent(weekday, key -> new TreeMap<>())
                    .computeIfAbsent(row.getHourOfDay(), key -> new ArrayList<>())
                    .add(row);
            days.add(row.getStatDate());
        }

        LocalDateTime computedAt = LocalDateTime.now().withNano(0);
        List<TrafficProfile> profiles = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, List<TrafficHourly>>> byWeekday : grouped.entrySet()) {
            Map<Integer, BigDecimal> avgByHour = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<TrafficHourly>> byHour : byWeekday.getValue().entrySet()) {
                avgByHour.put(byHour.getKey(), averageOrders(byHour.getValue()));
            }
            BigDecimal max = avgByHour.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            for (Map.Entry<Integer, BigDecimal> byHour : avgByHour.entrySet()) {
                TrafficProfile profile = new TrafficProfile();
                profile.setWeekday(byWeekday.getKey());
                profile.setHourOfDay(byHour.getKey());
                profile.setAvgOrderCount(byHour.getValue());
                profile.setAvgCustomerCount(averageCustomers(byWeekday.getValue().get(byHour.getKey())));
                profile.setSampleDays(byWeekday.getValue().get(byHour.getKey()).size());
                profile.setIsPeak(TrafficService.isPeak(byHour.getValue(), max));
                profile.setComputedAt(computedAt);
                profiles.add(profile);
            }
        }

        profileMapper.delete(null);
        for (TrafficProfile profile : profiles) {
            profileMapper.insert(profile);
        }
        log.info("[traffic] 重算周内客流规律：样本 {} 天 / {} 条记录，写入 {} 行（星期 × 小时）",
                days.size(), rows.size(), profiles.size());
        String note = "已按「星期 × 小时」聚合 " + days.size() + " 天客流样本，写入 " + profiles.size()
                + " 行；avg_order_count 按样本天数平均（没有记录的时段不进分母），is_peak = 该星期内 "
                + "avg_order_count ≥ 最高值 × 0.8（与 /api/traffic/peaks 同口径）。" + peakSummary(profiles);
        return new TrafficProfileResponse(computedAt, days.size(), profiles.size(), note, toRows(profiles));
    }

    /** 查询：不传 weekday 返回 7×24 全部；没有数据返回空数组并在 note 里说明（不是错误）。 */
    public TrafficProfileResponse query(Integer weekday) {
        if (weekday != null && (weekday < 1 || weekday > 7)) {
            throw BusinessException.badRequest("weekday 取值必须是 1~7（1=周一 … 7=周日），收到：" + weekday);
        }
        List<TrafficProfile> rows = profileMapper.selectList(Wrappers.<TrafficProfile>lambdaQuery()
                .eq(weekday != null, TrafficProfile::getWeekday, weekday)
                .orderByAsc(TrafficProfile::getWeekday)
                .orderByAsc(TrafficProfile::getHourOfDay));
        if (rows.isEmpty()) {
            return new TrafficProfileResponse(null, 0, 0,
                    "还没有周内客流规律数据：请先调用 POST /api/traffic/profile/recompute 重算（只读定时扫描也会重算派生数据）。",
                    List.of());
        }
        LocalDateTime computedAt = rows.stream().map(TrafficProfile::getComputedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        int sampleDays = rows.stream().mapToInt(row -> row.getSampleDays() == null ? 0 : row.getSampleDays())
                .max().orElse(0);
        String note = "来自 shop_traffic_profile 的周内规律：" + rows.size() + " 行，样本最多 " + sampleDays
                + " 天，最近重算 " + computedAt + "。" + peakSummary(rows);
        return new TrafficProfileResponse(computedAt, sampleDays, rows.size(), note, toRows(rows));
    }

    /** 星期几的人话标签：1 → "周一"。 */
    public static String weekdayLabel(Integer weekday) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            return "-";
        }
        return WEEKDAY_LABELS[weekday - 1];
    }

    // ------------------------------------------------------------------ 内部实现

    private static BigDecimal averageOrders(List<TrafficHourly> rows) {
        int sum = 0;
        for (TrafficHourly row : rows) {
            sum += row.getOrderCount() == null ? 0 : row.getOrderCount();
        }
        return divide(sum, rows.size());
    }

    private static BigDecimal averageCustomers(List<TrafficHourly> rows) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO.setScale(AVG_SCALE, RoundingMode.HALF_UP);
        }
        int sum = 0;
        int counted = 0;
        for (TrafficHourly row : rows) {
            if (row.getCustomerCount() != null) {
                sum += row.getCustomerCount();
                counted++;
            }
        }
        return counted == 0 ? BigDecimal.ZERO.setScale(AVG_SCALE, RoundingMode.HALF_UP) : divide(sum, counted);
    }

    private static BigDecimal divide(int sum, int count) {
        if (count <= 0) {
            return BigDecimal.ZERO.setScale(AVG_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), AVG_SCALE, RoundingMode.HALF_UP);
    }

    private static List<TrafficProfileRow> toRows(List<TrafficProfile> profiles) {
        List<TrafficProfileRow> rows = new ArrayList<>(profiles.size());
        for (TrafficProfile profile : profiles) {
            rows.add(new TrafficProfileRow(profile.getWeekday(), weekdayLabel(profile.getWeekday()),
                    profile.getHourOfDay(), profile.getAvgOrderCount(), profile.getAvgCustomerCount(),
                    profile.getSampleDays(), profile.getIsPeak(), profile.getComputedAt()));
        }
        return rows;
    }

    /** 每天高峰小时的人话汇总，例如"周一 09:00-10:00、17:00-18:00"。 */
    private static String peakSummary(List<TrafficProfile> profiles) {
        if (profiles.isEmpty()) {
            return "";
        }
        Map<Integer, List<Integer>> peaks = new TreeMap<>();
        for (TrafficProfile profile : profiles) {
            if (Boolean.TRUE.equals(profile.getIsPeak())) {
                peaks.computeIfAbsent(profile.getWeekday(), key -> new ArrayList<>()).add(profile.getHourOfDay());
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : peaks.entrySet()) {
            List<Integer> hours = entry.getValue();
            StringBuilder text = new StringBuilder(weekdayLabel(entry.getKey())).append(" 高峰 ");
            for (int i = 0; i < hours.size() && i < 6; i++) {
                int hour = hours.get(i);
                if (i > 0) {
                    text.append("、");
                }
                text.append(String.format("%02d:00-%02d:00", hour, (hour + 1) % 24));
            }
            parts.add(text.toString());
        }
        if (parts.isEmpty()) {
            return "各小时均值都为 0，未识别出高峰。";
        }
        return "高峰时段：" + String.join("；", parts) + "。";
    }
}
