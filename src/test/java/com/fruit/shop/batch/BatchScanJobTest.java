package com.fruit.shop.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.NearExpiryRow;
import com.fruit.shop.batch.dto.ScanResult;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读定时扫描验收（docs/07 第 2 节 + 第 3 节验收 5）。
 *
 * <p>边界：定时任务**只写"算出来的字段"和任务日志**，不写任何决策结果。本测试锁定：
 * <ol>
 *   <li>跑一轮后 {@code fruit_batch} 的派生指标被写入（freshness 等 + metrics_updated_at）；</li>
 *   <li>同一轮里 {@code pricing_suggestion / pricing_decision / push_log / sales_record / strategy_daily_stat}
 *       零新增，批次的 {@code status / remain_qty / init_qty / cost_price / scrap_qty} 一个都没变；</li>
 *   <li>{@code app.scan.enabled=false} 时一轮都不跑（什么都不写）；</li>
 *   <li>cron 入口确实存在且走 {@code app.scan.cron}（结构性），跑它同样零业务写入（行为性）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchScanJobTest {

    @Autowired
    private BatchScanJob scanJob;

    @Autowired
    private BatchService batchService;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private SalesRecordMapper salesRecordMapper;

    @Autowired
    private AppProperties props;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("验收 5：跑一轮只写派生指标；决策/推送/流水/统计零新增，批次业务字段不变")
    void scanOnlyWritesDerivedMetrics() {
        // 刻意选种子数据（2026-03-02 起）之前的业务日：本轮只应命中本用例的批次，
        // 断言不受同 JVM 里种子数据/其它测试的临期批次数量影响。
        LocalDate bizDate = LocalDate.of(2026, 1, 15);
        FruitItem item = ShopFixtures.item(itemMapper, "扫描测试香蕉", ShopFixtures.unique("扫描类"),
                "2.00", "4.50", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "30", "2.00");
        for (int i = 7; i >= 1; i--) {
            BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(i), item.getId(), batch.getId(), "9");
        }

        long suggestions = rows("pricing_suggestion");
        long decisions = rows("pricing_decision");
        long pushLogs = rows("push_log");
        long pushTasks = rows("push_task");
        long sales = rows("sales_record");
        long stats = rows("strategy_daily_stat");
        Map<Long, String> businessBefore = businessSnapshot();

        ScanResult result = scanJob.scanOnce(bizDate);

        assertThat(result).as("扫描开关默认开启，应返回本轮结果").isNotNull();
        assertThat(result.bizDate()).isEqualTo(bizDate);
        assertThat(result.scanned()).isGreaterThanOrEqualTo(1);
        assertThat(result.updated()).as("命中的临期批次都要写入派生指标").isEqualTo(result.scanned());
        assertThat(result.atRisk()).isGreaterThanOrEqualTo(1);
        assertThat(result.message()).contains("只写派生字段");

        // 派生指标已落库（与实时计算同一口径：0.4 / 3.333 / 18 / 12 / 有风险），
        // 且写入时间就是本轮扫描时间 → 证明本用例的批次确实在本轮命中。
        FruitBatch stored = batchMapper.selectById(batch.getId());
        assertThat(stored.getFreshness()).isEqualByComparingTo("0.4");
        assertThat(stored.getEstimatedSellableDays()).isEqualByComparingTo("3.333");
        assertThat(stored.getPlannedSaleQty()).isEqualByComparingTo("18");
        assertThat(stored.getExpectedScrapQty()).isEqualByComparingTo("12");
        assertThat(stored.getAtRisk()).isTrue();
        assertThat(stored.getMetricsUpdatedAt()).isEqualTo(result.metricsUpdatedAt());
        // "有风险清单"（同一口径实时计算）应包含它
        assertThat(batchService.atRiskList(bizDate, 2)).extracting(NearExpiryRow::batchId).contains(batch.getId());

        // 三类业务表（+建议/统计）零新增：扫描不会顺手生成决策或推送
        assertThat(rows("pricing_suggestion")).as("pricing_suggestion 不应新增").isEqualTo(suggestions);
        assertThat(rows("pricing_decision")).as("pricing_decision 不应新增").isEqualTo(decisions);
        assertThat(rows("push_log")).as("push_log 不应新增").isEqualTo(pushLogs);
        assertThat(rows("push_task")).as("push_task 不应新增").isEqualTo(pushTasks);
        assertThat(rows("sales_record")).as("sales_record 不应新增").isEqualTo(sales);
        assertThat(rows("strategy_daily_stat")).as("strategy_daily_stat 不应新增").isEqualTo(stats);
        // 业务状态（剩余量/状态/进价/报废）一个都没动
        assertThat(businessSnapshot()).isEqualTo(businessBefore);
        assertThat(stored.getStatus()).isEqualTo("ON_SALE");
        assertThat(stored.getRemainQty()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("app.scan.enabled=false：一轮都不跑，派生指标保持为空")
    void disabledSwitchSkipsScan() {
        LocalDate bizDate = LocalDate.of(2026, 1, 16);
        FruitItem item = ShopFixtures.item(itemMapper, "开关测试果", ShopFixtures.unique("开关类"),
                "2.00", "4.50", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "30", "2.00");
        BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(1), item.getId(), batch.getId(), "9");

        boolean original = props.getScan().isEnabled();
        try {
            props.getScan().setEnabled(false);
            assertThat(scanJob.scanOnce(bizDate)).as("关掉开关后不应扫描").isNull();
        } finally {
            props.getScan().setEnabled(original);
        }
        assertThat(batchMapper.selectById(batch.getId()).getMetricsUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("cron 入口存在且走 app.scan.cron；直接跑它同样不产生任何业务写入")
    void scheduledEntryPointIsReadOnly() throws Exception {
        Method method = BatchScanJob.class.getMethod("hourlyScan");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("小时级扫描必须是 @Scheduled 入口").isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.scan.cron:0 0 * * * *}");
        assertThat(props.getScan().getCron()).as("默认每小时").isEqualTo("0 0 * * * *");
        assertThat(props.getScan().isEnabled()).as("默认开启").isTrue();

        long decisions = rows("pricing_decision");
        long pushLogs = rows("push_log");
        long sales = rows("sales_record");
        Map<Long, String> businessBefore = businessSnapshot();

        scanJob.hourlyScan();

        assertThat(rows("pricing_decision")).isEqualTo(decisions);
        assertThat(rows("push_log")).isEqualTo(pushLogs);
        assertThat(rows("sales_record")).isEqualTo(sales);
        assertThat(businessSnapshot()).isEqualTo(businessBefore);
    }

    // ------------------------------------------------------------------ 工具

    private long rows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /** 所有批次的业务字段快照（刻意不含派生指标列）：扫描不该动其中任何一个。 */
    private Map<Long, String> businessSnapshot() {
        Map<Long, String> map = new LinkedHashMap<>();
        for (FruitBatch batch : batchMapper.selectList(Wrappers.<FruitBatch>lambdaQuery().orderByAsc(FruitBatch::getId))) {
            map.put(batch.getId(), batch.getStatus() + "|" + plain(batch.getInitQty()) + "|" + plain(batch.getRemainQty())
                    + "|" + plain(batch.getCostPrice()) + "|" + plain(batch.getScrapQty()));
        }
        return map;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.toPlainString();
    }
}
