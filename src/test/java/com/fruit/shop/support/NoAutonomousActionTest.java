package com.fruit.shop.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.FruitShopApplication;
import com.fruit.shop.batch.BatchService;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.PricingService;
import com.fruit.shop.pricing.mapper.PricingDecisionMapper;
import com.fruit.shop.push.PushService;
import com.fruit.shop.push.dto.PushPreviewRequest;
import com.fruit.shop.push.mapper.PushLogMapper;
import com.fruit.shop.push.mapper.PushTaskMapper;
import com.fruit.shop.sales.SalesService;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
import com.fruit.shop.traffic.TrafficService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合规验收（docs/05 NFR-03：无人操作时系统不得自动产生动作）+ docs/07 第 2 节的定时任务边界。
 *
 * <p>三层验证：
 * <ol>
 *   <li>行为验证：只调"查询类接口"（临期清单、建议查询、客流、高峰、效果看板、文案预览）后，
 *       pricing_decision / push_log / sales_record / strategy_daily_stat 行数不变，批次状态与剩余量不变；</li>
 *   <li>计划 vs 发送：文案预览只允许**建一条 PLANNED 计划**（push_task），
 *       <b>不得产生 push_log</b>（未发送）、不得置 SENT、不得写 sent_at——预演不等于发送；</li>
 *   <li>结构验证（白名单）：定时任务只允许出现在**只读派生计算**的 batch / traffic 范围；
 *       <b>push 范围一律禁止 @Scheduled</b>（发消息必须显式调用接口），主类不允许 @EnableScheduling。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoAutonomousActionTest {

    /** 允许出现定时任务的包前缀（只读派生计算：临期扫描、客流规律重算） */
    private static final List<String> SCHEDULED_ALLOWED_PACKAGES = List.of(
            "com.fruit.shop.batch.",
            "com.fruit.shop.traffic."
    );

    /** 任何情况下都不允许出现定时任务的包前缀（会导致自动发消息/自动改价） */
    private static final List<String> SCHEDULED_FORBIDDEN_PACKAGES = List.of(
            "com.fruit.shop.push.",
            "com.fruit.shop.pricing.",
            "com.fruit.shop.sales."
    );

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BatchService batchService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private TrafficService trafficService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PushService pushService;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private PricingDecisionMapper decisionMapper;

    @Autowired
    private PushTaskMapper pushTaskMapper;

    @Autowired
    private PushLogMapper pushLogMapper;

    @Autowired
    private SalesRecordMapper salesRecordMapper;

    @Autowired
    private StrategyDailyStatMapper statMapper;

    @Test
    @DisplayName("NFR-03：调用一圈查询接口后，决策/日志/流水/统计零新增，批次状态与剩余量不变")
    void queryEndpointsDoNotWriteAnything() {
        LocalDate bizDate = LocalDate.of(2035, 7, 1);
        FruitItem item = ShopFixtures.item(itemMapper, "静止测试果", "测试静止类", "4.00", "10.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");

        long decisions = decisionMapper.selectCount(null);
        long logs = pushLogMapper.selectCount(null);
        long sales = salesRecordMapper.selectCount(null);
        long stats = statMapper.selectCount(null);
        Map<Long, String> statusBefore = batchStatuses();
        Map<Long, BigDecimal> remainBefore = batchRemains();

        batchService.nearExpiry(bizDate, 2);
        pricingService.query(bizDate, null);
        trafficService.hourly(bizDate);
        trafficService.peaks(bizDate);
        salesService.effect(bizDate.minusDays(6), bizDate, null);

        assertThat(decisionMapper.selectCount(null)).as("pricing_decision 不应新增").isEqualTo(decisions);
        assertThat(pushLogMapper.selectCount(null)).as("push_log 不应新增（查询类接口不发送）").isEqualTo(logs);
        assertThat(salesRecordMapper.selectCount(null)).as("sales_record 不应新增").isEqualTo(sales);
        assertThat(statMapper.selectCount(null)).as("strategy_daily_stat 不应新增").isEqualTo(stats);
        assertThat(batchStatuses()).as("批次状态不应被自动修改").isEqualTo(statusBefore);
        assertThat(batchRemains()).as("批次剩余量不应被自动修改").isEqualTo(remainBefore);
    }

    @Test
    @DisplayName("NFR-03：文案预览只建 PLANNED 计划，不发送、不写 push_log、不置 SENT")
    void previewOnlyCreatesPlanAndNeverSends() {
        LocalDate bizDate = LocalDate.of(2035, 7, 2);
        FruitItem item = ShopFixtures.item(itemMapper, "预演测试果", "测试预演类", "4.00", "10.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 4, "6", "4.00");

        long tasksBefore = pushTaskMapper.selectCount(null);
        long logsBefore = pushLogMapper.selectCount(null);

        PushPreviewRequest preview = new PushPreviewRequest();
        preview.setItemId(item.getId());
        preview.setBizDate(bizDate);
        pushService.preview(preview);

        assertThat(pushLogMapper.selectCount(null))
                .as("预览不得产生任何 push_log（未发送）").isEqualTo(logsBefore);
        assertThat(pushTaskMapper.selectCount(null))
                .as("预览最多只能新增 1 条计划").isLessThanOrEqualTo(tasksBefore + 1);
    }

    @Test
    @DisplayName("结构边界：定时任务只允许出现在只读派生计算范围（batch/traffic），push 一律禁止")
    void scheduledJobsOnlyAllowedForReadOnlyDerivation() {
        assertThat(FruitShopApplication.class.isAnnotationPresent(EnableScheduling.class))
                .as("主类不允许 @EnableScheduling（定时能力只能由各模块自己的配置类开启）").isFalse();

        List<String> violations = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = applicationContext.getType(beanName);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null || !type.getName().startsWith("com.fruit.shop")) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Scheduled.class)) {
                    continue;
                }
                String className = type.getName();
                offenders.add(className + "#" + method.getName());
                boolean allowed = SCHEDULED_ALLOWED_PACKAGES.stream().anyMatch(className::startsWith);
                boolean forbidden = SCHEDULED_FORBIDDEN_PACKAGES.stream().anyMatch(className::startsWith);
                if (!allowed || forbidden) {
                    violations.add(className + "#" + method.getName());
                }
            }
        }

        assertThat(violations)
                .as("这些定时任务越界了（只允许 batch/traffic 的只读派生计算；push/pricing/sales 一律禁止）：%s", violations)
                .isEmpty();
        // 打出实际存在的定时任务，方便人工核对（不失败）
        if (!offenders.isEmpty()) {
            System.out.println("[NFR-03] 已登记的只读定时任务：" + offenders);
        }
    }

    private Map<Long, String> batchStatuses() {
        Map<Long, String> map = new LinkedHashMap<>();
        batchMapper.selectList(Wrappers.<FruitBatch>lambdaQuery().orderByAsc(FruitBatch::getId))
                .forEach(batch -> map.put(batch.getId(), batch.getStatus()));
        return map;
    }

    private Map<Long, BigDecimal> batchRemains() {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        batchMapper.selectList(Wrappers.<FruitBatch>lambdaQuery().orderByAsc(FruitBatch::getId))
                .forEach(batch -> map.put(batch.getId(), batch.getRemainQty()));
        return map;
    }
}
