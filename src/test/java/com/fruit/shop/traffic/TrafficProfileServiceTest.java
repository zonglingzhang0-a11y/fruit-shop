package com.fruit.shop.traffic;

import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import com.fruit.shop.traffic.dto.TrafficProfileResponse;
import com.fruit.shop.traffic.dto.TrafficProfileRow;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import com.fruit.shop.traffic.mapper.TrafficProfileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 周内客流规律测试（docs/07 第 1 节 D）。
 *
 * <p>每个用例用**自己独占的两周窗口**（每个星期几各出现 2 次）自造客流数据，
 * 断言：按样本天数平均、is_peak 沿用"≥ 该星期最高 × 0.8"、重算是全表重建不留陈旧单元格、
 * 查询路径不做任何写入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrafficProfileServiceTest {

    @Autowired
    private TrafficProfileService profileService;

    @Autowired
    private TrafficHourlyMapper trafficMapper;

    @Autowired
    private TrafficProfileMapper profileMapper;

    @Test
    @DisplayName("重算：按「星期 × 小时」聚合，avg 按样本天数平均，is_peak 用 0.8 阈值")
    void recomputeAggregatesByWeekdayAndHour() {
        LocalDate from = LocalDate.of(2035, 7, 2);
        LocalDate to = LocalDate.of(2035, 7, 15);
        fillTwoWeeks(from, to);

        TrafficProfileResponse response = profileService.recompute(from, to);

        assertThat(response.sampleDays()).as("窗口内 14 天都有客流记录").isEqualTo(14);
        assertThat(response.rowCount()).as("7 个星期几 × 3 个小时").isEqualTo(21);
        assertThat(response.computedAt()).isNotNull();
        assertThat(response.note()).contains("星期 × 小时").contains("样本天数");

        int weekday = from.getDayOfWeek().getValue();
        TrafficProfileRow hour9 = row(response, weekday, 9);
        TrafficProfileRow hour10 = row(response, weekday, 10);
        TrafficProfileRow hour20 = row(response, weekday, 20);

        assertThat(hour9.avgOrderCount()).isEqualByComparingTo("40");
        assertThat(hour9.avgCustomerCount()).isEqualByComparingTo("30");
        assertThat(hour9.sampleDays()).as("同一星期几在窗口里出现 2 次 → 该单元格样本 2 天").isEqualTo(2);
        assertThat(hour10.avgOrderCount()).isEqualByComparingTo("50");
        assertThat(hour20.avgOrderCount()).isEqualByComparingTo("10");

        // is_peak：该星期内最高均值 50 → 阈值 40 → 40 与 50 都算高峰（边界取闭区间）
        assertThat(hour10.isPeak()).isTrue();
        assertThat(hour9.isPeak()).as("恰好等于 50 × 0.8 = 40 → 高峰").isTrue();
        assertThat(hour20.isPeak()).isFalse();
        assertThat(TrafficService.PEAK_RATIO).isEqualTo(0.8);
        assertThat(TrafficService.isPeak(new BigDecimal("39.999"), new BigDecimal("50"))).isFalse();
        assertThat(TrafficService.isPeak(new BigDecimal("40"), new BigDecimal("50"))).isTrue();

        for (TrafficProfileRow profileRow : response.profiles()) {
            if (profileRow.isPeak()) {
                assertThat(profileRow.hourOfDay()).isIn(9, 10);
            }
        }
        assertThat(profileMapper.selectCount(null)).as("每个 (星期, 小时) 只有一行").isEqualTo(21);
    }

    @Test
    @DisplayName("查询路径：weekday 过滤、越界报人话、没有数据不报错")
    void queryReturnsStoredProfileAndValidatesWeekday() {
        LocalDate from = LocalDate.of(2035, 8, 6);
        LocalDate to = LocalDate.of(2035, 8, 19);
        fillTwoWeeks(from, to);
        profileService.recompute(from, to);

        TrafficProfileResponse all = profileService.query(null);
        assertThat(all.rowCount()).isEqualTo(21);
        assertThat(all.computedAt()).isNotNull();
        assertThat(all.note()).contains("shop_traffic_profile").contains("高峰时段");

        int weekday = from.getDayOfWeek().getValue();
        TrafficProfileResponse oneWeekday = profileService.query(weekday);
        assertThat(oneWeekday.profiles()).hasSize(3);
        assertThat(oneWeekday.profiles()).extracting(TrafficProfileRow::weekday).containsOnly(weekday);
        assertThat(oneWeekday.profiles()).extracting(TrafficProfileRow::weekdayLabel)
                .containsOnly(TrafficProfileService.weekdayLabel(weekday));
        assertThat(oneWeekday.profiles()).extracting(TrafficProfileRow::hourOfDay).containsExactly(9, 10, 20);

        assertThatThrownBy(() -> profileService.query(8))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1~7");

        // 查询不写库：行数与最近重算时间不变
        assertThat(profileMapper.selectCount(null)).isEqualTo(21);
    }

    @Test
    @DisplayName("重算是全表重建：换了窗口不会留下上一次的陈旧单元格，也不会撞唯一索引")
    void recomputeRebuildsTableWithoutStaleCells() {
        // 用 2036 年：原先的 2035-09-03/04 落在 BatchMetricsServiceTest 香蕉用例的近 7 天窗口里，
        // 两个测试共用内存库，执行顺序一变就会把香蕉的有效营业天数从 7 改成 2
        LocalDate firstFrom = LocalDate.of(2036, 9, 1);
        LocalDate firstTo = LocalDate.of(2036, 9, 2);
        for (LocalDate day = firstFrom; !day.isAfter(firstTo); day = day.plusDays(1)) {
            ShopFixtures.traffic(trafficMapper, day, 9, 100, 80, false);
        }
        TrafficProfileResponse first = profileService.recompute(firstFrom, firstTo);
        assertThat(first.rowCount()).isEqualTo(2);

        LocalDate secondFrom = LocalDate.of(2036, 9, 8);
        LocalDate secondTo = LocalDate.of(2036, 9, 9);
        for (LocalDate day = secondFrom; !day.isAfter(secondTo); day = day.plusDays(1)) {
            ShopFixtures.traffic(trafficMapper, day, 9, 20, 15, false);
            ShopFixtures.traffic(trafficMapper, day, 10, 60, 50, false);
        }
        TrafficProfileResponse second = profileService.recompute(secondFrom, secondTo);

        assertThat(second.rowCount()).isEqualTo(4);
        assertThat(second.sampleDays()).isEqualTo(2);
        assertThat(profileMapper.selectCount(null)).as("旧窗口的单元格已被清掉").isEqualTo(4);
        assertThat(profileService.query(null).profiles())
                .extracting(TrafficProfileRow::hourOfDay).containsExactlyInAnyOrder(9, 10, 9, 10);
        // 新窗口里 hour 10 高于 hour 9 → 只有 hour 10 是高峰
        assertThat(profileService.query(null).profiles())
                .filteredOn(TrafficProfileRow::isPeak)
                .extracting(TrafficProfileRow::hourOfDay)
                .containsOnly(10);
        assertThatThrownBy(() -> profileService.recompute(secondTo, secondFrom))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能晚于");
    }

    /** 两周窗口，每个日历日固定 3 个时段：9 点 40 单、10 点 50 单、20 点 10 单。 */
    private void fillTwoWeeks(LocalDate from, LocalDate to) {
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            ShopFixtures.traffic(trafficMapper, day, 9, 40, 30, false);
            ShopFixtures.traffic(trafficMapper, day, 10, 50, 40, false);
            ShopFixtures.traffic(trafficMapper, day, 20, 10, 8, false);
        }
    }

    private static TrafficProfileRow row(TrafficProfileResponse response, int weekday, int hour) {
        List<TrafficProfileRow> rows = response.profiles().stream()
                .filter(row -> row.weekday() == weekday && row.hourOfDay() == hour)
                .toList();
        assertThat(rows).as("找不到 weekday=%s hour=%s 的规律行", weekday, hour).hasSize(1);
        return rows.get(0);
    }
}
