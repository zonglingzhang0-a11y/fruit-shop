package com.fruit.shop.pricing;

import com.fruit.shop.batch.BatchMetricsFixtures;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.domain.PricingDecision;
import com.fruit.shop.pricing.domain.PricingSuggestion;
import com.fruit.shop.pricing.dto.DecisionRequest;
import com.fruit.shop.pricing.dto.DecisionResponse;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.dto.SuggestionGenerateResponse;
import com.fruit.shop.pricing.dto.SuggestionView;
import com.fruit.shop.pricing.mapper.PricingDecisionMapper;
import com.fruit.shop.pricing.mapper.PricingSuggestionMapper;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
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
 * 定价约束验收（docs/07 第 1 节 B + 第 3 节验收 2）。
 *
 * <ul>
 *   <li>最低折扣线（默认 0.60）：对 FIXED/RULE/MODEL **所有策略**生效，触底时
 *       {@code price_floor_hit=true}、{@code raw_discount_rate} 记录原始档位、reason 说明"触到店主设定的最低折扣线"；</li>
 *   <li>每日调价上限（默认 2）：同批次当天第 3 次调价被拒，**生成**与**店主改价**两条路径都拦；
 *       {@code adjust_seq} 如实递增；幂等重跑不占次数；</li>
 *   <li>{@code pricing_decision.reject_reason}：店主不接受时留痕。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingConstraintsTest {

    @Autowired
    private PricingService pricingService;

    @Autowired
    private PricingAdjustGuard adjustGuard;

    @Autowired
    private AppProperties props;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private PricingSuggestionMapper suggestionMapper;

    @Autowired
    private PricingDecisionMapper decisionMapper;

    @Autowired
    private SalesRecordMapper salesRecordMapper;

    @Test
    @DisplayName("验收 2：最低折扣线对 FIXED/RULE/MODEL 都生效，触底留痕并可读解释")
    void floorAppliesToEveryStrategy() {
        // 三个策略各用一个独立业务日，避免互相触发"每天最多调价 2 次"。
        // 日期刻意取种子数据（2026-03-02 起）之前，命中批次数量可控、断言不被种子数据淹没。
        LocalDate ruleDate = LocalDate.of(2026, 1, 10);
        LocalDate fixedDate = LocalDate.of(2026, 1, 11);
        LocalDate modelDate = LocalDate.of(2026, 1, 12);

        // RULE：days_left=0 的规则档是 0.50，被 0.60 底线拦住
        FruitItem ruleItem = ShopFixtures.item(itemMapper, "底线规则果", ShopFixtures.unique("底线类"),
                "4.00", "10.00", 5);
        var ruleBatch = ShopFixtures.batch(batchMapper, ruleItem.getId(), ruleDate.minusDays(5), 5, "6", "4.00");
        generate(ruleDate, "RULE");
        SuggestionView rule = mine(ruleDate, "RULE", ruleBatch.getId());
        assertThat(rule.daysLeft()).isZero();
        assertThat(rule.discountRate()).isEqualByComparingTo("0.60");
        assertThat(rule.rawDiscountRate()).as("记录被约束前的原始档位").isEqualByComparingTo("0.50");
        assertThat(rule.priceFloorHit()).isTrue();
        assertThat(rule.suggestedPrice()).isEqualByComparingTo("6.00");
        assertThat(rule.reason()).contains("触到店主设定的最低折扣线");

        // FIXED：固定 7 折，本身高于底线 → 不触底、raw == 实际档位
        FruitItem fixedItem = ShopFixtures.item(itemMapper, "底线固定果", ShopFixtures.unique("底线类"),
                "4.00", "10.00", 5);
        var fixedBatch = ShopFixtures.batch(batchMapper, fixedItem.getId(), fixedDate.minusDays(5), 5, "6", "4.00");
        generate(fixedDate, "FIXED");
        SuggestionView fixed = mine(fixedDate, "FIXED", fixedBatch.getId());
        assertThat(fixed.discountRate()).isEqualByComparingTo("0.70");
        assertThat(fixed.priceFloorHit()).isFalse();
        assertThat(fixed.rawDiscountRate()).isEqualByComparingTo("0.70");
        assertThat(fixed.reason()).doesNotContain("触到店主设定的最低折扣线");

        // MODEL：产物缺失回退 RULE → 同样受底线约束
        FruitItem modelItem = ShopFixtures.item(itemMapper, "底线模型果", ShopFixtures.unique("底线类"),
                "4.00", "10.00", 5);
        var modelBatch = ShopFixtures.batch(batchMapper, modelItem.getId(), modelDate.minusDays(5), 5, "6", "4.00");
        generate(modelDate, "MODEL");
        SuggestionView model = mine(modelDate, "MODEL", modelBatch.getId());
        assertThat(model.discountRate()).isEqualByComparingTo("0.60");
        assertThat(model.priceFloorHit()).isTrue();
        assertThat(model.rawDiscountRate()).isEqualByComparingTo("0.50");
        assertThat(model.reason()).contains("触到店主设定的最低折扣线");

        // 落库留痕（不是只在响应里算的）
        PricingSuggestion stored = suggestionMapper.selectById(rule.id());
        assertThat(stored.getPriceFloorHit()).isTrue();
        assertThat(stored.getRawDiscountRate()).isEqualByComparingTo("0.50");
        assertThat(stored.getDiscountRate()).isEqualByComparingTo("0.60");
        assertThat(stored.getAdjustSeq()).isEqualTo(1);
        assertThat(props.minDiscountRate()).isEqualByComparingTo("0.60");
        assertThat(props.maxAdjustPerDay()).isEqualTo(2);
    }

    @Test
    @DisplayName("建议里带临期判定指标快照（freshness / 预计可售天数 / 预计报废）")
    void suggestionCarriesFreshnessSnapshot() {
        LocalDate bizDate = LocalDate.of(2026, 1, 15);
        FruitItem item = ShopFixtures.item(itemMapper, "快照测试香蕉", ShopFixtures.unique("快照类"),
                "2.00", "4.50", 5);
        var batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "30", "2.00");
        for (int i = 7; i >= 1; i--) {
            BatchMetricsFixtures.sale(salesRecordMapper, bizDate.minusDays(i), item.getId(), batch.getId(), "9");
        }
        generate(bizDate, "RULE");

        SuggestionView view = mine(bizDate, "RULE", batch.getId());
        assertThat(view.freshness()).isEqualByComparingTo("0.4");
        assertThat(view.estimatedSellableDays()).isEqualByComparingTo("3.333");
        assertThat(view.expectedScrapQty()).isEqualByComparingTo("12");
        assertThat(view.reason()).contains("临期判定").contains("新鲜度");

        PricingSuggestion stored = suggestionMapper.selectById(view.id());
        assertThat(stored.getFreshness()).isEqualByComparingTo("0.4");
        assertThat(stored.getEstimatedSellableDays()).isEqualByComparingTo("3.333");
        assertThat(stored.getExpectedScrapQty()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("店主改价：低于最低折扣线被拒（恰好等于底线可通过），拒绝时不落决策")
    void manualRepricingBelowFloorIsRejected() {
        LocalDate bizDate = LocalDate.of(2026, 1, 20);
        FruitItem item = ShopFixtures.item(itemMapper, "改价底线果", ShopFixtures.unique("改价类"),
                "4.00", "10.00", 5);
        var batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");
        generate(bizDate, "RULE");
        SuggestionView suggestion = mine(bizDate, "RULE", batch.getId());
        assertThat(suggestion.suggestedPrice()).isEqualByComparingTo("7.00");

        // 5.50 ÷ 10.00 = 0.55 < 0.60 → 拒绝，并告诉店主最低可售价 6.00
        DecisionRequest below = new DecisionRequest();
        below.setSuggestionId(suggestion.id());
        below.setFinalPrice(new BigDecimal("5.50"));
        assertThatThrownBy(() -> pricingService.decide(below))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最低折扣线")
                .hasMessageContaining("6.00");
        assertThat(decisionsOf(suggestion.id())).as("被拒的改价不能落决策").isZero();

        // 边界：恰好等于底线（6.00）通过
        DecisionRequest atFloor = new DecisionRequest();
        atFloor.setSuggestionId(suggestion.id());
        atFloor.setFinalPrice(new BigDecimal("6.00"));
        DecisionResponse response = pricingService.decide(atFloor);
        assertThat(response.finalPrice()).isEqualByComparingTo("6.00");
        assertThat(response.accepted()).isFalse();
        assertThat(response.changed()).isTrue();
        assertThat(response.rejectReason()).isNotBlank();

        PricingDecision stored = decisionMapper.selectById(response.decisionId());
        assertThat(stored.getFinalPrice()).isEqualByComparingTo("6.00");
        assertThat(stored.getRejectReason()).isNotBlank();
    }

    @Test
    @DisplayName("验收 2：同批次当天第 3 次调价在生成路径被拒；adjust_seq 如实递增；幂等重跑不占次数")
    void thirdAdjustmentRejectedOnGeneratePath() {
        // 日期取种子数据之前，命中批次只有本用例的批次；断言仍以"本批次"为准，避免依赖全局计数。
        LocalDate bizDate = LocalDate.of(2026, 1, 25);
        FruitItem item = ShopFixtures.item(itemMapper, "调价上限果", ShopFixtures.unique("上限类"),
                "4.00", "10.00", 5);
        var batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");

        SuggestionGenerateResponse first = generate(bizDate, "RULE");
        assertThat(first.generated()).isGreaterThanOrEqualTo(1);
        assertThat(first.skippedByAdjustLimit()).isZero();
        assertThat(suggestionsOf(batch.getId())).isEqualTo(1);

        SuggestionGenerateResponse second = generate(bizDate, "FIXED");
        assertThat(second.generated()).isGreaterThanOrEqualTo(1);
        assertThat(second.skippedByAdjustLimit()).isZero();
        assertThat(suggestionsOf(batch.getId())).isEqualTo(2);

        // 幂等重跑（同一天同策略同批次）：不算调价次数，也不改动已有建议
        SuggestionGenerateResponse rerun = generate(bizDate, "RULE");
        assertThat(rerun.generated()).isZero();
        assertThat(rerun.skippedByAdjustLimit()).as("幂等重跑不该被当成第 3 次调价").isZero();
        assertThat(suggestionsOf(batch.getId())).isEqualTo(2);

        // 第 3 次调价 → 拒绝，并给出人话原因
        SuggestionGenerateResponse third = generate(bizDate, "MODEL");
        assertThat(third.generated()).isZero();
        assertThat(third.skippedByAdjustLimit()).isGreaterThanOrEqualTo(1);
        assertThat(third.adjustLimitNote()).contains("每天最多调价 2 次");
        assertThat(suggestionsOf(batch.getId())).isEqualTo(2);

        // adjust_seq 如实递增：第 1 条 = 1、第 2 条 = 2
        List<PricingSuggestion> stored = suggestionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<PricingSuggestion>lambdaQuery()
                        .eq(PricingSuggestion::getBatchId, batch.getId())
                        .orderByAsc(PricingSuggestion::getId));
        assertThat(stored).extracting(PricingSuggestion::getAdjustSeq).containsExactly(1, 2);
        assertThat(adjustGuard.adjustCount(bizDate, batch.getId())).isEqualTo(2);
        assertThat(adjustGuard.canAdjust(bizDate, batch.getId())).isFalse();
        // 生成与改价两条路径共用同一句人话原因
        assertThat(adjustGuard.limitReason(batch.getBatchNo(), bizDate, 2))
                .contains("第 3 次调价被拒绝")
                .contains("每天最多调价 2 次");
    }

    @Test
    @DisplayName("验收 2：店主改价路径同样受上限约束（按建议价成交不算调价，改价才计数）")
    void thirdAdjustmentRejectedOnDecidePath() {
        LocalDate bizDate = LocalDate.of(2026, 1, 26);
        FruitItem item = ShopFixtures.item(itemMapper, "改价上限果", ShopFixtures.unique("上限类"),
                "4.00", "10.00", 5);
        var batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");
        generate(bizDate, "RULE");
        SuggestionView suggestion = mine(bizDate, "RULE", batch.getId());

        // 第 1 次：按建议价成交（采纳，没改价）→ 不算调价，允许
        DecisionRequest accept = new DecisionRequest();
        accept.setSuggestionId(suggestion.id());
        DecisionResponse accepted = pricingService.decide(accept);
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.changed()).isFalse();
        assertThat(adjustGuard.adjustCount(bizDate, batch.getId())).isEqualTo(1);

        // 第 2 次：改价到 6.50（0.65 ≥ 底线）→ 允许
        DecisionRequest first = new DecisionRequest();
        first.setSuggestionId(suggestion.id());
        first.setFinalPrice(new BigDecimal("6.50"));
        assertThat(pricingService.decide(first).finalPrice()).isEqualByComparingTo("6.50");
        assertThat(adjustGuard.adjustCount(bizDate, batch.getId())).isEqualTo(2);

        // 第 3 次：再改价 → 拒绝，人话原因
        DecisionRequest second = new DecisionRequest();
        second.setSuggestionId(suggestion.id());
        second.setFinalPrice(new BigDecimal("6.20"));
        assertThatThrownBy(() -> pricingService.decide(second))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("每天最多调价 2 次")
                .hasMessageContaining(batch.getBatchNo());
        assertThat(decisionsOf(suggestion.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("reject_reason：不接受建议时留痕（显式原因优先，否则用 reason 兜底；采纳时为 null）")
    void rejectReasonIsPersisted() {
        LocalDate bizDate = LocalDate.of(2026, 1, 27);
        String category = ShopFixtures.unique("拒绝类");
        FruitItem explicit = ShopFixtures.item(itemMapper, "拒绝原因果", category, "4.00", "10.00", 5);
        FruitItem fallback = ShopFixtures.item(itemMapper, "拒绝兜底果", category, "4.00", "10.00", 5);
        FruitItem accepted = ShopFixtures.item(itemMapper, "拒绝采纳果", category, "4.00", "10.00", 5);
        var explicitBatch = ShopFixtures.batch(batchMapper, explicit.getId(), bizDate.minusDays(3), 5, "6", "4.00");
        var fallbackBatch = ShopFixtures.batch(batchMapper, fallback.getId(), bizDate.minusDays(3), 5, "6", "4.00");
        var acceptedBatch = ShopFixtures.batch(batchMapper, accepted.getId(), bizDate.minusDays(3), 5, "6", "4.00");
        generate(bizDate, "RULE");

        DecisionRequest withReason = new DecisionRequest();
        withReason.setSuggestionId(mine(bizDate, "RULE", explicitBatch.getId()).id());
        withReason.setFinalPrice(new BigDecimal("6.50"));
        withReason.setRejectReason("嫌折扣不够深");
        DecisionResponse explicitResponse = pricingService.decide(withReason);
        assertThat(explicitResponse.rejectReason()).isEqualTo("嫌折扣不够深");

        DecisionRequest withoutReason = new DecisionRequest();
        withoutReason.setSuggestionId(mine(bizDate, "RULE", fallbackBatch.getId()).id());
        withoutReason.setFinalPrice(new BigDecimal("6.50"));
        withoutReason.setReason("明天有雨怕卖不动");
        DecisionResponse fallbackResponse = pricingService.decide(withoutReason);
        assertThat(fallbackResponse.rejectReason()).isEqualTo("明天有雨怕卖不动");

        DecisionRequest acceptAll = new DecisionRequest();
        acceptAll.setSuggestionId(mine(bizDate, "RULE", acceptedBatch.getId()).id());
        DecisionResponse acceptedResponse = pricingService.decide(acceptAll);
        assertThat(acceptedResponse.accepted()).isTrue();
        assertThat(acceptedResponse.rejectReason()).isNull();

        assertThat(decisionMapper.selectById(explicitResponse.decisionId()).getRejectReason())
                .isEqualTo("嫌折扣不够深");
        assertThat(decisionMapper.selectById(acceptedResponse.decisionId()).getRejectReason()).isNull();
    }

    // ------------------------------------------------------------------ 工具

    private SuggestionGenerateResponse generate(LocalDate bizDate, String strategy) {
        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy(strategy);
        return pricingService.generate(request);
    }

    private SuggestionView mine(LocalDate bizDate, String strategy, Long batchId) {
        return pricingService.query(bizDate, strategy).stream()
                .filter(view -> batchId.equals(view.batchId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没找到该批次的建议：batchId=" + batchId));
    }

    /** 该批次的建议条数（只统计自己造的数据，库里还有种子/其他测试的数据）。 */
    private long suggestionsOf(Long batchId) {
        return suggestionMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<PricingSuggestion>lambdaQuery().eq(PricingSuggestion::getBatchId, batchId));
    }

    /** 某条建议下的决策条数。 */
    private long decisionsOf(Long suggestionId) {
        return decisionMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<PricingDecision>lambdaQuery().eq(PricingDecision::getSuggestionId, suggestionId));
    }
}
