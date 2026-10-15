package com.fruit.shop.pricing;

import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.dto.ModelInputResponse;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.dto.SuggestionView;
import com.fruit.shop.pricing.strategy.ModelPricingStrategy;
import com.fruit.shop.sales.SalesService;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
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
        assertThat(snap.salesFrom()).isBeforeOrEqualTo(bizDate.minusDays(9));
        assertThat(snap.batches()).anySatisfy(b -> assertThat(b.batchId()).isEqualTo(early.getId()));
        List<ModelInputResponse.DailySale> mine = snap.sales().stream()
                .filter(s -> early.getId().equals(s.batchId())).toList();
        assertThat(mine).extracting(ModelInputResponse.DailySale::bizDate)
                .containsExactlyInAnyOrder(bizDate.minusDays(8), bizDate.minusDays(1));
        assertThat(snap.sales()).allSatisfy(s -> assertThat(s.bizDate()).isBefore(bizDate));
    }

    // ------------------------------------------------------------------ 工具

    /** 剩余 1 天的临期批次（一定会生成建议）。 */
    private FruitBatch nearExpiryBatch(LocalDate bizDate, String name) {
        FruitItem item = ShopFixtures.item(itemMapper, name, ShopFixtures.unique("模型类"), "4.00", "10.00", 5);
        return ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 5, "8", "4.00");
    }

    private Path artifact(LocalDate bizDate, String modelStatus, Long batchId, String rate) throws Exception {
        Path file = tempDir.resolve("model-" + bizDate + "-" + modelStatus + "-" + batchId + ".json");
        Files.writeString(file, """
                {"schemaVersion": 1, "bizDate": "%s", "generatedAt": "2034-01-01T09:00:00",
                 "modelVersion": "pricing_model.py@test", "modelStatus": "%s", "modelNote": "测试产物",
                 "inputSource": "test",
                 "suggestions": [{"batchId": %d, "itemCode": "NOPE", "discountRate": %s, "expectedQty": 3.5,
                                  "reason": "测试模型理由"}]}
                """.formatted(bizDate, modelStatus, batchId, rate));
        return file;
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
        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("MODEL");
        pricingService.generate(request);
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
        r.setBatchId(batch.getId());
        r.setQuantity(new BigDecimal(qty));
        r.setUnitPrice(new BigDecimal("9.00"));
        r.setIsDiscount(false);
        r.setIsReturn(false);
        r.setSource("TEST");
        salesMapper.insert(r);
    }
}
