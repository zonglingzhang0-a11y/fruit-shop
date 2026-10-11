package com.fruit.shop.traffic;

import com.fruit.shop.support.ShopFixtures;
import com.fruit.shop.traffic.dto.HourlyRow;
import com.fruit.shop.traffic.dto.PeakResponse;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时段客流与高峰识别测试。
 * 重点覆盖：高峰前 1 小时的推送建议口径、is_peak 缺失时的自动判定、无数据不报错、跨零点推送小时。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrafficServiceTest {

    @Autowired
    private TrafficService trafficService;

    @Autowired
    private TrafficHourlyMapper trafficMapper;

    @Test
    @DisplayName("按日客流：有数据时补全 0~23 点，前端可直接画图")
    void hourlyPadsToFullDay() {
        LocalDate date = LocalDate.of(2035, 6, 1);
        ShopFixtures.traffic(trafficMapper, date, 9, 40, 30, false);
        ShopFixtures.traffic(trafficMapper, date, 10, 120, 90, true);

        List<HourlyRow> rows = trafficService.hourly(date);

        assertThat(rows).hasSize(24);
        assertThat(rows.get(0).hourOfDay()).isZero();
        assertThat(rows.get(0).orderCount()).isZero();
        assertThat(rows.get(0).isPeak()).isFalse();
        assertThat(rows.get(9).orderCount()).isEqualTo(40);
        assertThat(rows.get(9).customerCount()).isEqualTo(30);
        assertThat(rows.get(10).orderCount()).isEqualTo(120);
        assertThat(rows.get(10).isPeak()).isTrue();
        assertThat(rows.get(23).orderCount()).isZero();
    }

    @Test
    @DisplayName("按日客流：当天没有数据时返回空数组而不是报错")
    void hourlyReturnsEmptyWhenNoData() {
        assertThat(trafficService.hourly(LocalDate.of(1990, 1, 1))).isEmpty();
    }

    @Test
    @DisplayName("高峰：优先用表里的 is_peak，推送时间 = 高峰 - 1 小时")
    void peaksUseStoredFlagAndSuggestOneHourEarlier() {
        LocalDate date = LocalDate.of(2035, 6, 2);
        ShopFixtures.traffic(trafficMapper, date, 9, 30, 25, false);
        ShopFixtures.traffic(trafficMapper, date, 10, 100, 80, true);
        ShopFixtures.traffic(trafficMapper, date, 11, 50, 40, false);

        PeakResponse peaks = trafficService.peaks(date);

        assertThat(peaks.dataAvailable()).isTrue();
        assertThat(peaks.derived()).isFalse();
        assertThat(peaks.peakHours()).containsExactly(10);
        assertThat(peaks.suggestPushHours()).containsExactly(9);
        assertThat(peaks.suggestPushTimes()).containsExactly("09:00");
        assertThat(peaks.note()).contains("高峰前 1 小时").contains("09:00");
        assertThat(peaks.peakHourDetails()).hasSize(1);
        assertThat(peaks.peakHourDetails().get(0).suggestPushHour()).isEqualTo(9);
        assertThat(peaks.peakHourDetails().get(0).orderCount()).isEqualTo(100);
        assertThat(peaks.maxOrderCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("高峰：没有 is_peak 标志时按\"成交笔数 >= 当日最高 × 0.8\"自动判定，并如实标注 derived=true")
    void peaksAreDerivedWithoutFlag() {
        LocalDate date = LocalDate.of(2035, 6, 3);
        ShopFixtures.traffic(trafficMapper, date, 9, 10, 8, false);
        ShopFixtures.traffic(trafficMapper, date, 10, 100, 80, false);
        ShopFixtures.traffic(trafficMapper, date, 11, 85, 70, false);
        ShopFixtures.traffic(trafficMapper, date, 12, 20, 15, false);

        PeakResponse peaks = trafficService.peaks(date);

        assertThat(peaks.dataAvailable()).isTrue();
        assertThat(peaks.derived()).isTrue();
        assertThat(peaks.derivationRule()).contains("0.8");
        assertThat(peaks.peakHours()).containsExactly(10, 11);
        assertThat(peaks.suggestPushHours()).containsExactly(9, 10);
        assertThat(peaks.suggestPushTimes()).containsExactly("09:00", "10:00");
        assertThat(peaks.note()).contains("is_peak");
    }

    @Test
    @DisplayName("高峰：零点高峰的建议推送时间落到前一天 23:00（跨零点处理）")
    void midnightPeakSuggestsPushAtPreviousDay23() {
        LocalDate date = LocalDate.of(2035, 6, 4);
        ShopFixtures.traffic(trafficMapper, date, 0, 100, 90, true);
        ShopFixtures.traffic(trafficMapper, date, 12, 10, 8, false);

        PeakResponse peaks = trafficService.peaks(date);

        assertThat(peaks.peakHours()).containsExactly(0);
        assertThat(peaks.suggestPushHours()).containsExactly(23);
        assertThat(peaks.suggestPushTimes()).containsExactly("23:00");
    }

    @Test
    @DisplayName("无数据时的建议：不硬编高峰小时，明确说\"没有数据、系统保持空闲\"")
    void peaksWithoutDataGiveNoAdvice() {
        PeakResponse peaks = trafficService.peaks(LocalDate.of(1990, 1, 1));

        assertThat(peaks.dataAvailable()).isFalse();
        assertThat(peaks.peakHours()).isEmpty();
        assertThat(peaks.suggestPushHours()).isEmpty();
        assertThat(peaks.note()).contains("暂无客流数据").contains("不会自动发消息");
    }
}
