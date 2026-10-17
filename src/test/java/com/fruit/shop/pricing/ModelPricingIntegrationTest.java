package com.fruit.shop.pricing;

import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.dto.DecisionRequest;
import com.fruit.shop.pricing.dto.ModelInputResponse;
import com.fruit.shop.pricing.dto.SuggestionGenerateResponse;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.dto.SuggestionView;
import com.fruit.shop.pricing.strategy.ModelPricingStrategy;
import com.fruit.shop.sales.SalesService;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在线 MODEL 接入（Issue #2）：产物可用时真的用模型；缺失/过期/模型不可用/没覆盖该批次时如实回退并记为 RULE；
 * 效果看板按实际生效策略归属；模型输入快照只给业务日之前的数据。
 *
 * <p>业务日用 2034 年：与其它共用内存库的测试错开。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelPricingIntegrationTest {

    @TempDir
    Path tempDir;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private ModelPricingStrategy modelStrategy;

    @Autowired
    private ModelInputService modelInputService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private AppProperties props;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private SalesRecordMapper salesMapper;

    @Test
    @DisplayName("产物业务日一致、覆盖该批次：建议来自模型，effectiveStrategy=MODEL，理由带模型版本")
    void usableArtifactDrivesModelSuggestion() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 10);
        FruitBatch batch = nearExpiryBatch(bizDate, "模型生效桃");
        Path artifact = artifact(bizDate, "ok", batch.getId(), "0.8");

        SuggestionView view = withModelFile(artifact, () -> generateMine(bizDate, batch));

        assertThat(view.discountRate()).isEqualByComparingTo("0.80");
        assertThat(view.strategy()).isEqualTo("MODEL");
        assertThat(view.effectiveStrategy()).isEqualTo("MODEL");
        assertThat(view.reason()).contains("pricing_model.py@test").contains("测试模型理由");
        assertThat(view.expectedQty()).isEqualByComparingTo("3.5");
    }

    @Test
    @DisplayName("产物是另一个业务日的：视为过期，回退规则版并记为 RULE，理由写清日期不一致")
    void staleArtifactFallsBackToRule() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 11);
        FruitBatch batch = nearExpiryBatch(bizDate, "模型过期桃");
        Path artifact = artifact(bizDate.minusDays(1), "ok", batch.getId(), "0.8");

        SuggestionView view = withModelFile(artifact, () -> generateMine(bizDate, batch));

        assertThat(view.strategy()).isEqualTo("MODEL");
        assertThat(view.effectiveStrategy()).isEqualTo("RULE");
        assertThat(view.reason()).contains("不一致").contains("回退规则版");
    }

    @Test
    @DisplayName("产物标记模型不可用（样本不足）：不用里面任何建议，回退规则版")
    void unusableModelStatusFallsBack() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 12);
        FruitBatch batch = nearExpiryBatch(bizDate, "模型不可用桃");
        Path artifact = artifact(bizDate, "insufficient_obs", batch.getId(), "0.8");

        SuggestionView view = withModelFile(artifact, () -> generateMine(bizDate, batch));

        assertThat(view.effectiveStrategy()).isEqualTo("RULE");
        assertThat(view.reason()).contains("insufficient_obs");
    }

    @Test
    @DisplayName("产物里没有该批次/单品：回退规则版；JSON 损坏同样回退，且都不影响生成")
    void missingRowAndBrokenFileFallBack() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 13);
        FruitBatch batch = nearExpiryBatch(bizDate, "模型没覆盖桃");
        Path otherBatch = artifact(bizDate, "ok", -999L, "0.8");

        SuggestionView view = withModelFile(otherBatch, () -> generateMine(bizDate, batch));
        assertThat(view.effectiveStrategy()).isEqualTo("RULE");
        assertThat(view.reason()).contains("没有该批次");

        LocalDate nextDay = bizDate.plusDays(1);
        FruitBatch batch2 = nearExpiryBatch(nextDay, "模型坏文件桃");
        Path broken = tempDir.resolve("broken.json");
        Files.writeString(broken, "{ not json");
        SuggestionView view2 = withModelFile(broken, () -> generateMine(nextDay, batch2));
        assertThat(view2.effectiveStrategy()).isEqualTo("RULE");
        assertThat(view2.reason()).contains("无法读取");
    }

    @Test
    @DisplayName("模型状态接口：同一份产物对它的业务日可用、对别的业务日不可用，并说明原因")
    void statusReportsUsability() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 15);
        Path artifact = artifact(bizDate, "ok", 1L, "0.8");

        ModelPricingStrategy.ModelStatus same = withModelFile(artifact, () -> modelStrategy.status(bizDate));
        ModelPricingStrategy.ModelStatus other = withModelFile(artifact, () -> modelStrategy.status(bizDate.plusDays(1)));

        assertThat(same.usable()).isTrue();
        assertThat(same.artifactBizDate()).isEqualTo(bizDate);
        assertThat(same.modelVersion()).isEqualTo("pricing_model.py@test");
        assertThat(same.suggestionCount()).isEqualTo(1);
        assertThat(other.usable()).isFalse();
        assertThat(other.message()).contains("不一致");
    }

    @Test
    @DisplayName("效果看板按实际生效策略归属：MODEL 回退规则版的建议，其销售算到 RULE；模型生效的算到 MODEL")
    void salesAreAttributedToEffectiveStrategy() throws Exception {
        LocalDate fallbackDay = LocalDate.of(2034, 4, 16);
        FruitBatch fallbackBatch = nearExpiryBatch(fallbackDay, "归属回退桃");
        withModelFile(artifact(fallbackDay.minusDays(1), "ok", fallbackBatch.getId(), "0.8"),
                () -> generateMine(fallbackDay, fallbackBatch));

        LocalDate modelDay = LocalDate.of(2034, 4, 17);
        FruitBatch modelBatch = nearExpiryBatch(modelDay, "归属模型桃");
        withModelFile(artifact(modelDay, "ok", modelBatch.getId(), "0.8"), () -> generateMine(modelDay, modelBatch));

        assertThat(salesService.feedback(sale(fallbackDay, fallbackBatch)).strategy()).isEqualTo("RULE");
        assertThat(salesService.feedback(sale(modelDay, modelBatch)).strategy()).isEqualTo("MODEL");
    }

    @Test
    @DisplayName("仓库自带的产物（scripts/export_model_suggestions.py 导出）能被后端解析，对它的业务日可用")
    void committedArtifactIsReadable() {
        ModelPricingStrategy.ModelStatus status = modelStrategy.status(LocalDate.of(2026, 6, 29));

        assertThat(status.present()).as("data/seed/pricing_model.json 应随仓库提供").isTrue();
        assertThat(status.usable()).as(status.message()).isTrue();
        assertThat(status.modelVersion()).startsWith("pricing_model.py@");
        assertThat(status.suggestionCount()).isPositive();
    }

    @Test
    @DisplayName("模型输入快照：只给业务日之前的成交；销量从窗口内批次最早到货日起给，便于重建期初库存")
    void modelInputHasNoLookahead() {
        LocalDate bizDate = LocalDate.of(2034, 5, 20);
        FruitItem item = ShopFixtures.item(itemMapper, "快照测试梨", ShopFixtures.unique("快照类"), "4.00", "10.00", 10);
        // 到货在窗口（7 天）起点之前：它在窗口外的销量也要给，否则期初库存算多了
        FruitBatch early = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(9), 10, "50", "4.00");
        record(item, early, bizDate.minusDays(8), "5");
        record(item, early, bizDate.minusDays(1), "3");
        record(item, early, bizDate, "7");            // 业务日当天：不能出现在快照里
        record(item, early, bizDate.plusDays(1), "9"); // 未来：同上

        ModelInputResponse snap = modelInputService.snapshot(bizDate, 7);

        assertThat(snap.historyFrom()).isEqualTo(bizDate.minusDays(7));
        assertThat(snap.historyTo()).isEqualTo(bizDate.minusDays(1));
        assertThat(snap.minDiscountRate()).as("导出脚本按后端的底线决策").isEqualByComparingTo(props.minDiscountRate());
        assertThat(snap.salesFrom()).isBeforeOrEqualTo(bizDate.minusDays(9));
        assertThat(snap.batches()).anySatisfy(b -> assertThat(b.batchId()).isEqualTo(early.getId()));
        List<ModelInputResponse.DailySale> mine = snap.sales().stream()
                .filter(s -> early.getId().equals(s.batchId())).toList();
        assertThat(mine).extracting(ModelInputResponse.DailySale::bizDate)
                .containsExactlyInAnyOrder(bizDate.minusDays(8), bizDate.minusDays(1));
        assertThat(snap.sales()).allSatisfy(s -> assertThat(s.bizDate()).isBefore(bizDate));
    }

    @Test
    @DisplayName("产物按 batchNo 匹配（batchId 对不上）：仍是批次级，用产物的期望销量")
    void matchesByBatchNo() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 20);
        FruitBatch batch = nearExpiryBatch(bizDate, "批次号匹配桃");
        Path artifact = artifactWithRow(bizDate, "ok", """
                {"batchId": -1, "batchNo": "%s", "discountRate": 0.8, "expectedQty": 3.5, "reason": "批次号匹配"}
                """.formatted(batch.getBatchNo()));

        SuggestionView view = withModelFile(artifact, () -> generateMine(bizDate, batch));

        assertThat(view.effectiveStrategy()).isEqualTo("MODEL");
        assertThat(view.discountRate()).isEqualByComparingTo("0.80");
        assertThat(view.expectedQty()).isEqualByComparingTo("3.5");
    }

    @Test
    @DisplayName("产物只按单品匹配上：用单品的货架折扣，期望销量不照搬别的批次的分摊值，按本批次现存估算")
    void itemLevelMatchEstimatesExpectedQty() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 21);
        FruitBatch batch = nearExpiryBatch(bizDate, "单品匹配桃");
        FruitItem item = itemMapper.selectById(batch.getItemId());
        Path artifact = artifactWithRow(bizDate, "ok", """
                {"batchId": -1, "itemCode": "%s", "discountRate": 0.8, "expectedQty": 99, "reason": "单品匹配"}
                """.formatted(item.getItemCode()));

        SuggestionView view = withModelFile(artifact, () -> generateMine(bizDate, batch));

        assertThat(view.effectiveStrategy()).isEqualTo("MODEL");
        assertThat(view.discountRate()).isEqualByComparingTo("0.80");
        assertThat(view.expectedQty()).isEqualByComparingTo(
                PricingPolicy.estimateExpectedQty(batch.getRemainQty(), new BigDecimal("0.80"), view.daysLeft()));
    }

    @Test
    @DisplayName("模型在底线以内决策时报告了不受约束的档位：建议记 price_floor_hit 和原始档位")
    void modelFloorDecisionIsAudited() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 22);
        FruitBatch batch = nearExpiryBatch(bizDate, "模型底线桃");
        Path artifact = artifactWithRow(bizDate, "ok", """
                {"batchId": %d, "discountRate": 0.6, "rawDiscountRate": 0.5, "expectedQty": 3, "reason": "底线内决策"}
                """.formatted(batch.getId()));

        SuggestionView view = withModelFile(artifact, () -> generateMine(bizDate, batch));

        assertThat(view.discountRate()).isEqualByComparingTo("0.60");
        assertThat(view.priceFloorHit()).isTrue();
        assertThat(view.rawDiscountRate()).isEqualByComparingTo("0.50");
        assertThat(view.reason()).contains("最低折扣线").contains("本可以更低到 5折");
    }

    @Test
    @DisplayName("早先回退了规则版、现在产物可用：再次生成用模型重做（计一次调价），列表只显示最新那条")
    void fallbackIsRegeneratedOnceModelIsUsable() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 23);
        FruitBatch batch = nearExpiryBatch(bizDate, "回退重做桃");
        SuggestionView first = withModelFile(artifact(bizDate.minusDays(1), "ok", batch.getId(), "0.8"),
                () -> generateMine(bizDate, batch));
        assertThat(first.effectiveStrategy()).isEqualTo("RULE");

        Path usable = artifact(bizDate, "ok", batch.getId(), "0.8");
        SuggestionGenerateResponse res = withModelFile(usable, () -> pricingService.generate(request(bizDate)));
        assertThat(res.strategyNote()).contains("已用模型重新生成");
        List<SuggestionView> mine = pricingService.query(bizDate, "MODEL").stream()
                .filter(v -> batch.getId().equals(v.batchId())).toList();
        assertThat(mine).singleElement().satisfies(v -> {
            assertThat(v.effectiveStrategy()).isEqualTo("MODEL");
            assertThat(v.discountRate()).isEqualByComparingTo("0.80");
            assertThat(v.adjustSeq()).isEqualTo(2);
        });

        // 再生成一次：已经是模型给的，幂等跳过
        SuggestionGenerateResponse again = withModelFile(usable, () -> pricingService.generate(request(bizDate)));
        assertThat(pricingService.query(bizDate, "MODEL").stream()
                .filter(v -> batch.getId().equals(v.batchId())).toList()).singleElement()
                .satisfies(v -> assertThat(v.id()).isEqualTo(mine.get(0).id()));
        assertThat(again.generated()).isZero();
        assertThat(again.skippedByAdjustLimit()).as("应按幂等跳过，而不是撞上调价上限才没生成").isZero();
    }

    @Test
    @DisplayName("产物可用但仍没覆盖早先回退的批次：不重新生成（不白占调价次数），响应里说明")
    void uncoveredFallbackIsNotRegenerated() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 26);
        FruitBatch batch = nearExpiryBatch(bizDate, "仍未覆盖桃");
        Path usableOther = artifact(bizDate, "ok", -999L, "0.8");
        SuggestionView first = withModelFile(usableOther, () -> generateMine(bizDate, batch));
        assertThat(first.effectiveStrategy()).isEqualTo("RULE");

        SuggestionGenerateResponse again = withModelFile(usableOther, () -> pricingService.generate(request(bizDate)));

        assertThat(again.strategyNote()).contains("仍没有该批次");
        assertThat(pricingService.query(bizDate, "MODEL").stream()
                .filter(v -> batch.getId().equals(v.batchId())).toList()).singleElement()
                .satisfies(v -> assertThat(v.id()).isEqualTo(first.id()));
    }

    @Test
    @DisplayName("回退的建议店主已经决策过、或产物仍不可用：不重新生成，响应里说明原因")
    void decidedOrStillUnusableFallbackIsKept() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 24);
        FruitBatch batch = nearExpiryBatch(bizDate, "回退保留桃");
        Path stale = artifact(bizDate.minusDays(1), "ok", batch.getId(), "0.8");
        SuggestionView first = withModelFile(stale, () -> generateMine(bizDate, batch));

        SuggestionGenerateResponse stillStale = withModelFile(stale, () -> pricingService.generate(request(bizDate)));
        assertThat(stillStale.strategyNote()).contains("本次未重新生成").contains("不一致");

        DecisionRequest decision = new DecisionRequest();
        decision.setSuggestionId(first.id());
        decision.setFinalPrice(first.suggestedPrice());
        decision.setAccepted(true);
        pricingService.decide(decision);
        SuggestionGenerateResponse decided = withModelFile(artifact(bizDate, "ok", batch.getId(), "0.8"),
                () -> pricingService.generate(request(bizDate)));
        assertThat(decided.strategyNote()).contains("店主已对其做出决策");
        assertThat(pricingService.query(bizDate, "MODEL").stream()
                .filter(v -> batch.getId().equals(v.batchId())).toList()).singleElement()
                .satisfies(v -> assertThat(v.id()).isEqualTo(first.id()));
    }

    @Test
    @DisplayName("按 batchId 命中但批次号/单品对不上（id 被重新分配）：不用这行，回退规则版")
    void batchIdHitWithDifferentBatchNoIsIgnored() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 27);
        FruitBatch batch = nearExpiryBatch(bizDate, "id复用桃");
        Path artifact = artifactWithRow(bizDate, "ok", """
                {"batchId": %d, "batchNo": "OTHER-BATCH", "itemCode": "OTHER-ITEM", "discountRate": 0.7,
                 "expectedQty": 77, "reason": "别的批次的建议"}
                """.formatted(batch.getId()));

        SuggestionView view = withModelFile(artifact, () -> generateMine(bizDate, batch));

        assertThat(view.effectiveStrategy()).isEqualTo("RULE");
        assertThat(view.reason()).doesNotContain("别的批次的建议");
    }

    @Test
    @DisplayName("产物的最低折扣线与后端配置不一致、或没写底线：整份不用，回退规则版并说明怎么重新导出")
    void floorMismatchMakesArtifactUnusable() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 28);
        FruitBatch batch = nearExpiryBatch(bizDate, "底线不一致桃");
        String row = """
                {"batchId": %d, "discountRate": 0.8, "expectedQty": 3, "reason": "测试"}
                """.formatted(batch.getId());

        Path mismatch = artifactWithFloor(bizDate, "ok", "\"minDiscountRate\": 0.5,", row);
        ModelPricingStrategy.ModelStatus status = withModelFile(mismatch, () -> modelStrategy.status(bizDate));
        assertThat(status.usable()).isFalse();
        assertThat(status.message()).contains("0.5").contains("--min-discount-rate 0.6");
        SuggestionView view = withModelFile(mismatch, () -> generateMine(bizDate, batch));
        assertThat(view.effectiveStrategy()).isEqualTo("RULE");

        Path noFloor = artifactWithFloor(bizDate, "ok", "", row);
        assertThat(withModelFile(noFloor, () -> modelStrategy.status(bizDate)).message()).contains("没有写最低折扣线");
    }

    @Test
    @DisplayName("回退的建议想用模型重做但已到每日调价上限：响应里说明是上限拦住的，而不是什么都不说")
    void regenerationBlockedByAdjustLimitIsExplained() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 29);
        FruitBatch batch = nearExpiryBatch(bizDate, "上限拦住桃");
        SuggestionGenerateRequest rule = new SuggestionGenerateRequest();
        rule.setBizDate(bizDate);
        rule.setStrategy("RULE");
        pricingService.generate(rule);                                           // 第 1 次调价
        withModelFile(artifact(bizDate.minusDays(1), "ok", batch.getId(), "0.8"),
                () -> generateMine(bizDate, batch));                               // 第 2 次：回退规则版

        SuggestionGenerateResponse res = withModelFile(artifact(bizDate, "ok", batch.getId(), "0.8"),
                () -> pricingService.generate(request(bizDate)));

        assertThat(res.skippedByAdjustLimit()).isPositive();
        assertThat(res.strategyNote()).contains("本次未重新生成").contains("上限");
    }

    @Test
    @DisplayName("回退的建议已被模型重做：再对旧建议做决策返回 409，避免旧页面上的决策被列表隐藏却被推送取用")
    void decidingSupersededSuggestionIsRejected() throws Exception {
        LocalDate bizDate = LocalDate.of(2034, 4, 30);
        FruitBatch batch = nearExpiryBatch(bizDate, "旧建议决策桃");
        SuggestionView old = withModelFile(artifact(bizDate.minusDays(1), "ok", batch.getId(), "0.8"),
                () -> generateMine(bizDate, batch));
        withModelFile(artifact(bizDate, "ok", batch.getId(), "0.8"), () -> pricingService.generate(request(bizDate)));

        DecisionRequest decision = new DecisionRequest();
        decision.setSuggestionId(old.id());
        decision.setFinalPrice(old.suggestedPrice());
        decision.setAccepted(true);

        assertThatThrownBy(() -> pricingService.decide(decision))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被重新生成")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("模型输入快照：没关联批次的散卖记录，batchId 原样为 null（不能读成 0）")
    void unbatchedSaleHasNullBatchId() {
        LocalDate bizDate = LocalDate.of(2034, 5, 25);
        FruitItem item = ShopFixtures.item(itemMapper, "散卖测试梨", ShopFixtures.unique("散卖类"), "4.00", "10.00", 10);
        record(item, null, bizDate.minusDays(2), "2");

        ModelInputResponse snap = modelInputService.snapshot(bizDate, 7);

        assertThat(snap.sales()).filteredOn(s -> item.getItemCode().equals(s.itemCode()))
                .singleElement().satisfies(s -> assertThat(s.batchId()).isNull());
    }

    // ------------------------------------------------------------------ 工具

    /** 剩余 1 天的临期批次（一定会生成建议）。 */
    private FruitBatch nearExpiryBatch(LocalDate bizDate, String name) {
        FruitItem item = ShopFixtures.item(itemMapper, name, ShopFixtures.unique("模型类"), "4.00", "10.00", 5);
        return ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 5, "8", "4.00");
    }

    private Path artifact(LocalDate bizDate, String modelStatus, Long batchId, String rate) throws Exception {
        return artifactWithRow(bizDate, modelStatus, """
                {"batchId": %d, "discountRate": %s, "expectedQty": 3.5, "reason": "测试模型理由"}
                """.formatted(batchId, rate));
    }

    private Path artifactWithRow(LocalDate bizDate, String modelStatus, String rowJson) throws Exception {
        return artifactWithFloor(bizDate, modelStatus, "\"minDiscountRate\": 0.60,", rowJson);
    }

    /** floorField 是产物顶层的底线字段（含结尾逗号），传空串表示产物没写底线。 */
    private Path artifactWithFloor(LocalDate bizDate, String modelStatus, String floorField, String rowJson)
            throws Exception {
        Path file = Files.createTempFile(tempDir, "model-" + bizDate + "-" + modelStatus + "-", ".json");
        Files.writeString(file, """
                {"schemaVersion": 1, "bizDate": "%s", "generatedAt": "2034-01-01T09:00:00",
                 "modelVersion": "pricing_model.py@test", "modelStatus": "%s", "modelNote": "测试产物",
                 "inputSource": "test", %s
                 "suggestions": [%s]}
                """.formatted(bizDate, modelStatus, floorField, rowJson));
        return file;
    }

    private static SuggestionGenerateRequest request(LocalDate bizDate) {
        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("MODEL");
        return request;
    }

    private <T> T withModelFile(Path file, Supplier<T> body) {
        String original = props.getPricing().getModelFile();
        props.getPricing().setModelFile(file.toString());
        try {
            return body.get();
        } finally {
            props.getPricing().setModelFile(original);
        }
    }

    private SuggestionView generateMine(LocalDate bizDate, FruitBatch batch) {
        pricingService.generate(request(bizDate));
        return pricingService.query(bizDate, "MODEL").stream()
                .filter(v -> batch.getId().equals(v.batchId()))
                .findFirst().orElseThrow();
    }

    private SalesFeedbackRequest sale(LocalDate bizDate, FruitBatch batch) {
        SalesFeedbackRequest request = new SalesFeedbackRequest();
        request.setBizDate(bizDate);
        request.setBatchId(batch.getId());
        request.setQuantity(BigDecimal.ONE);
        request.setUnitPrice(new BigDecimal("8.00"));
        return request;
    }

    private void record(FruitItem item, FruitBatch batch, LocalDate day, String qty) {
        SalesRecord r = new SalesRecord();
        r.setBizDate(day);
        r.setSaleTime(day.atTime(10, 0));
        r.setItemId(item.getId());
        r.setBatchId(batch == null ? null : batch.getId());
        r.setQuantity(new BigDecimal(qty));
        r.setUnitPrice(new BigDecimal("9.00"));
        r.setIsDiscount(false);
        r.setIsReturn(false);
        r.setSource("TEST");
        salesMapper.insert(r);
    }
}
