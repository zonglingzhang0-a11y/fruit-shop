package com.fruit.shop.batch;

import com.fruit.shop.batch.domain.BatchMetrics;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.NearExpiryRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 临期判定指标测试（docs/07 第 1 节 A + 第 3 节验收 1）。
 *
 * <p>核心是**参考架构的香蕉例子可复现**：剩余 2 天、剩余库存 30、日均销量 9、保质期 5
 * → 新鲜度 0.4、预计可售 3.333 天、预计销量 18、预计报废 12、有风险。
 * 另外覆盖：样本不足 3 天的品类兜底口径、卖不动（无销量）的边界、临期清单接口返回指标且不写库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchMetricsServiceTest {

    @Autowired
    private BatchMetricsService metricsService;

    @Autowired
    private BatchService batchService;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private SalesRecordMapper salesRecordMapper;

    @Test
    @DisplayName("验收 1：参考架构的香蕉例子可复现（2 天 / 库存 30 / 日均 9 / 保质期 5）")
    void bananaExampleReproducesReferenceArchitecture() {
        LocalDate bizDate = LocalDate.of(2035, 9, 10);
        FruitItem item = ShopFixtures.item(itemMapper, "临期测试香蕉", ShopFixtures.unique("香蕉类"),
                "2.00", "4.50", 5);
        // 到货 = 业务日 - 3，保质期 5 → 到期日 = 业务日 + 2（剩余 2 天）
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "30", "2.00");
        // 近 7 天每天卖 9 → 日均 9（有效营业天数 7；无客流数据时按整窗营业）
        for (int i = 7; i >= 1; i--) {
            BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(i), item.getId(), batch.getId(), "9");
        }

        BatchMetrics metrics = metricsService.compute(bizDate, batch);

        assertThat(metrics.daysLeft()).isEqualTo(2);
        assertThat(metrics.shelfLifeDays()).isEqualTo(5);
        assertThat(metrics.freshness()).isEqualByComparingTo("0.4");
        assertThat(metrics.dailyAvgQty()).isEqualByComparingTo("9");
        assertThat(metrics.estimatedSellableDays()).isEqualByComparingTo("3.333");
        assertThat(metrics.plannedSaleQty()).isEqualByComparingTo("18");
        assertThat(metrics.expectedScrapQty()).isEqualByComparingTo("12");
        assertThat(metrics.atRisk()).as("预计可售 3.333 天 > 剩余 2 天 → 有风险").isTrue();
        assertThat(metrics.sampleDays()).isEqualTo(7);
        assertThat(metrics.businessDays()).isEqualTo(7);
        assertThat(metrics.fallbackUsed()).isFalse();
        assertThat(metrics.reason()).contains("新鲜度").contains("有风险").contains("近 7 天日均销量 9");
    }

    @Test
    @DisplayName("样本不足 3 天：走「品类同期日均 × 该单品近 3 天占比」兜底，并在 reason 里注明")
    void underSampledItemFallsBackToCategoryAverage() {
        LocalDate bizDate = LocalDate.of(2035, 9, 20);
        String category = ShopFixtures.unique("兜底类");
        FruitItem item = ShopFixtures.item(itemMapper, "兜底测试果", category, "3.00", "9.00", 6);
        FruitItem sibling = ShopFixtures.item(itemMapper, "兜底同品类果", category, "3.00", "9.00", 6);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 6, "20", "3.00");

        // 该单品近 7 天只有 2 天有销量（样本 2 < 3）：10 件
        BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(1), item.getId(), batch.getId(), "5");
        BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(2), item.getId(), batch.getId(), "5");
        // 同品类其它单品近 7 天 30 件，且落在近 3 天窗口内
        BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(3), sibling.getId(), null, "30");

        BatchMetrics metrics = metricsService.compute(bizDate, batch);

        // 品类日均 = 40 ÷ 7 = 5.7143；近 3 天占比 = 10 ÷ 40 = 0.25 → 兜底日均 1.4286
        assertThat(metrics.fallbackUsed()).isTrue();
        assertThat(metrics.sampleDays()).isEqualTo(2);
        assertThat(metrics.dailyAvgQty()).isEqualByComparingTo("1.4286");
        assertThat(metrics.estimatedSellableDays()).isEqualByComparingTo("14");
        assertThat(metrics.plannedSaleQty()).isEqualByComparingTo("2.86");
        assertThat(metrics.expectedScrapQty()).isEqualByComparingTo("17.14");
        assertThat(metrics.atRisk()).isTrue();
        assertThat(metrics.reason())
                .as("兜底口径必须写在 reason 里")
                .contains("样本不足 3 天")
                .contains("兜底口径")
                .contains("品类同期日均")
                .contains("该单品近 3 天占比");
    }

    @Test
    @DisplayName("近 7 天没有净销量：预计可售天数视为无穷（null）、报废 = 全部现存，判为有风险")
    void noSalesMeansEverythingIsAtRisk() {
        LocalDate bizDate = LocalDate.of(2035, 9, 25);
        String category = ShopFixtures.unique("无销类");
        FruitItem item = ShopFixtures.item(itemMapper, "无销测试果", category, "3.00", "9.00", 4);
        FruitItem sibling = ShopFixtures.item(itemMapper, "无销同品类果", category, "3.00", "9.00", 4);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 4, "8", "3.00");
        // 品类里有别的单品在卖，但这个单品一件没卖 → 兜底占比 0
        BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(1), sibling.getId(), null, "20");

        BatchMetrics metrics = metricsService.compute(bizDate, batch);

        assertThat(metrics.dailyAvgQty()).isEqualByComparingTo("0");
        assertThat(metrics.estimatedSellableDays()).isNull();
        assertThat(metrics.plannedSaleQty()).isEqualByComparingTo("0");
        assertThat(metrics.expectedScrapQty()).isEqualByComparingTo("8");
        assertThat(metrics.atRisk()).isTrue();
        assertThat(metrics.reason()).contains("卖不动");
    }

    @Test
    @DisplayName("退货按负销量计入日均（避免把退货当销量）")
    void returnsAreSubtractedFromDailyAverage() {
        LocalDate bizDate = LocalDate.of(2035, 9, 28);
        FruitItem item = ShopFixtures.item(itemMapper, "退货测试果", ShopFixtures.unique("退货类"),
                "3.00", "9.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "3.00");
        for (int i = 7; i >= 1; i--) {
            BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(i), item.getId(), batch.getId(), "10");
        }
        BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(1), item.getId(), batch.getId(),
                new BigDecimal("7"), true);

        BatchMetrics metrics = metricsService.compute(bizDate, batch);

        // 70 - 7 = 63 → 63 ÷ 7 = 9
        assertThat(metrics.dailyAvgQty()).isEqualByComparingTo("9");
        assertThat(metrics.reason()).contains("净销量");
    }

    @Test
    @DisplayName("临期清单接口返回临期判定指标，且查询路径不写库（metrics_updated_at 仍为空）")
    void nearExpiryListCarriesMetricsWithoutWriting() {
        LocalDate bizDate = LocalDate.of(2035, 9, 30);
        FruitItem item = ShopFixtures.item(itemMapper, "清单测试香蕉", ShopFixtures.unique("清单类"),
                "2.00", "4.50", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "30", "2.00");
        for (int i = 7; i >= 1; i--) {
            BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(i), item.getId(), batch.getId(), "9");
        }

        List<NearExpiryRow> rows = batchService.nearExpiry(bizDate, 2).stream()
                .filter(row -> batch.getId().equals(row.batchId()))
                .toList();

        assertThat(rows).hasSize(1);
        NearExpiryRow row = rows.get(0);
        assertThat(row.daysLeft()).isEqualTo(2);
        assertThat(row.freshness()).isEqualByComparingTo("0.4");
        assertThat(row.estimatedSellableDays()).isEqualByComparingTo("3.333");
        assertThat(row.plannedSaleQty()).isEqualByComparingTo("18");
        assertThat(row.expectedScrapQty()).isEqualByComparingTo("12");
        assertThat(row.atRisk()).isTrue();
        assertThat(row.metricsUpdatedAt()).isNotNull();
        assertThat(row.metricsReason()).contains("新鲜度");

        // 有风险清单只包含 at_risk=true 的行
        List<NearExpiryRow> risky = batchService.atRiskList(bizDate, 2);
        assertThat(risky).extracting(NearExpiryRow::batchId).contains(batch.getId());
        assertThat(risky).allSatisfy(item2 -> assertThat(item2.atRisk()).isTrue());

        // 查询接口零写库：派生列没有被顺手写进数据库（落库是定时扫描的职责）
        FruitBatch stored = batchMapper.selectById(batch.getId());
        assertThat(stored.getMetricsUpdatedAt()).isNull();
        assertThat(stored.getFreshness()).isNull();
        assertThat(stored.getRemainQty()).isEqualByComparingTo("30");
        assertThat(stored.getStatus()).isEqualTo("ON_SALE");
    }
}
