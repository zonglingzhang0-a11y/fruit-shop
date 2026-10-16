package com.fruit.shop.pricing;

import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.domain.PricingDecision;
import com.fruit.shop.pricing.dto.DecisionRequest;
import com.fruit.shop.pricing.dto.DecisionResponse;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.dto.SuggestionGenerateResponse;
import com.fruit.shop.pricing.dto.SuggestionView;
import com.fruit.shop.pricing.mapper.PricingDecisionMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定价建议与决策留痕测试。
 * 重点覆盖：候选折扣档（只能在 6 个档位里挑）、不许涨价、按剩余天数分档、存量压力、幂等生成、决策落库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingServiceTest {

    @Autowired
    private PricingService pricingService;

    @Autowired
    private AppProperties props;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private PricingDecisionMapper decisionMapper;

    @Test
    @DisplayName("RULE：剩余天数越少折扣越深，且只取候选档、绝不高于原价，reason 必须是人话")
    void ruleSuggestionsFollowDaysLeftAndStayInCandidateSet() {
        LocalDate bizDate = LocalDate.of(2035, 2, 10);
        FruitItem item = ShopFixtures.item(itemMapper, "规则测试柑橘", "测试规则类", "4.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(8), 8, "5", "4.00"); // days_left = 0
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(7), 8, "5", "4.00"); // days_left = 1
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "5", "4.00"); // days_left = 2

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");
        SuggestionGenerateResponse response = pricingService.generate(request);

        assertThat(response.bizDate()).isEqualTo(bizDate);
        assertThat(response.strategy()).isEqualTo("RULE");
        assertThat(response.generated()).isGreaterThanOrEqualTo(3);

        List<SuggestionView> mine = mine(bizDate, "RULE", item.getId());
        assertThat(mine).hasSize(3);
        Map<Integer, SuggestionView> byDaysLeft = mine.stream()
                .collect(Collectors.toMap(SuggestionView::daysLeft, Function.identity()));
        // days_left=0 的规则档是 5 折，但被最低折扣线 0.60 拦住（docs/07 第 1 节 B）→ 挂 6 折并留痕
        assertThat(byDaysLeft.get(0).discountRate()).isEqualByComparingTo("0.60");
        assertThat(byDaysLeft.get(0).priceFloorHit()).isTrue();
        assertThat(byDaysLeft.get(0).rawDiscountRate()).isEqualByComparingTo("0.50");
        assertThat(byDaysLeft.get(0).reason()).contains("最低折扣线");
        assertThat(byDaysLeft.get(1).discountRate()).isEqualByComparingTo("0.60");
        assertThat(byDaysLeft.get(1).priceFloorHit()).isFalse();
        assertThat(byDaysLeft.get(2).discountRate()).isEqualByComparingTo("0.70");

        for (SuggestionView view : mine) {
            assertThat(PricingPolicy.isCandidate(view.discountRate()))
                    .as("折扣必须是候选档之一，实际 %s", view.discountRate()).isTrue();
            assertThat(view.discountRate()).isLessThanOrEqualTo(BigDecimal.ONE);
            assertThat(view.suggestedPrice()).isLessThanOrEqualTo(view.originalPrice());
            assertThat(view.suggestedPrice()).isEqualByComparingTo(view.originalPrice()
                    .multiply(view.discountRate()).setScale(2, RoundingMode.HALF_UP));
            assertThat(view.reason()).isNotBlank();
            assertThat(view.expectedQty()).isNotNull();
            assertThat(view.itemName()).isEqualTo(item.getItemName());
            assertThat(view.batchNo()).isNotBlank();
            assertThat(view.strategy()).isEqualTo("RULE");
            assertThat(view.decided()).isFalse();
            assertThat(view.finalPrice()).isNull();
        }
        assertThat(byDaysLeft.get(0).reason()).contains("到期");
    }

    @Test
    @DisplayName("RULE 存量压力：现存超过阈值时再降一档")
    void stockPressureLowersOneStep() {
        LocalDate bizDate = LocalDate.of(2035, 2, 11);
        FruitItem item = ShopFixtures.item(itemMapper, "压力测试果", "测试压力类", "3.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "30", "3.00"); // days_left=2，现存 30 > 阈值 20

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");
        pricingService.generate(request);

        List<SuggestionView> mine = mine(bizDate, "RULE", item.getId());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).daysLeft()).isEqualTo(2);
        assertThat(mine.get(0).discountRate()).isEqualByComparingTo("0.60");
        assertThat(mine.get(0).reason()).contains("存量偏大");

        // 低于阈值时回到 7 折（同一策略、同一天，换一个商品对照）
        FruitItem small = ShopFixtures.item(itemMapper, "小量测试果", "测试压力类", "3.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, small.getId(), bizDate.minusDays(6), 8, "5", "3.00");
        pricingService.generate(request);
        List<SuggestionView> smallRows = mine(bizDate, "RULE", small.getId());
        assertThat(smallRows).hasSize(1);
        assertThat(smallRows.get(0).discountRate()).isEqualByComparingTo("0.70");
    }

    @Test
    @DisplayName("FIXED：不看剩余天数，统一使用配置的固定折扣档（仍然不涨价）")
    void fixedStrategyUsesConfiguredDiscount() {
        LocalDate bizDate = LocalDate.of(2035, 2, 12);
        FruitItem item = ShopFixtures.item(itemMapper, "固定折扣果", "测试固定类", "3.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(8), 8, "5", "3.00"); // days_left = 0
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "5", "3.00"); // days_left = 2

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("FIXED");
        pricingService.generate(request);

        BigDecimal expected = PricingPolicy.toCandidate(props.getPricing().getFixedDiscount());
        List<SuggestionView> mine = mine(bizDate, "FIXED", item.getId());
        assertThat(mine).hasSize(2);
        assertThat(mine).allSatisfy(view -> {
            assertThat(view.discountRate()).isEqualByComparingTo(expected);
            assertThat(view.suggestedPrice()).isLessThanOrEqualTo(view.originalPrice());
            assertThat(view.reason()).contains("基线");
        });
    }

    @Test
    @DisplayName("MODEL：产物缺失时回退 RULE，但结果仍必须是候选档且给出人话理由")
    void modelStrategyFallsBackWithoutArtifact() {
        LocalDate bizDate = LocalDate.of(2035, 2, 13);
        FruitItem item = ShopFixtures.item(itemMapper, "模型测试果", "测试模型类", "4.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(7), 8, "5", "4.00"); // days_left = 1

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("MODEL");
        SuggestionGenerateResponse response = pricingService.generate(request);

        assertThat(response.strategy()).isEqualTo("MODEL");
        List<SuggestionView> mine = mine(bizDate, "MODEL", item.getId());
        assertThat(mine).hasSize(1);
        SuggestionView view = mine.get(0);
        assertThat(PricingPolicy.isCandidate(view.discountRate())).isTrue();
        assertThat(view.suggestedPrice()).isLessThanOrEqualTo(view.originalPrice());
        assertThat(view.reason()).isNotBlank();
        // 仓库里的产物是 2026-06-29 的（或者没有产物）：对 2035 年的业务日一定不可用 → 如实回退并记为 RULE
        assertThat(view.reason()).contains("回退规则版");
        assertThat(view.effectiveStrategy()).isEqualTo("RULE");
        assertThat(response.effectiveStrategies()).containsEntry("RULE", response.generated());
        assertThat(response.strategyNote()).contains("实际由其它策略给出");
    }

    @Test
    @DisplayName("生成是幂等的：同一天同一策略同一批次不会重复插入")
    void generateIsIdempotent() {
        LocalDate bizDate = LocalDate.of(2035, 2, 14);
        FruitItem item = ShopFixtures.item(itemMapper, "幂等测试果", "测试幂等类", "4.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "5", "4.00");

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");

        pricingService.generate(request);
        assertThat(mine(bizDate, "RULE", item.getId())).hasSize(1);

        pricingService.generate(request);
        assertThat(mine(bizDate, "RULE", item.getId())).hasSize(1);

        // 不同策略互不影响：同一天再生成 FIXED，会各留一条
        request.setStrategy("FIXED");
        pricingService.generate(request);
        assertThat(mine(bizDate, "RULE", item.getId())).hasSize(1);
        assertThat(mine(bizDate, "FIXED", item.getId())).hasSize(1);
        assertThat(pricingService.query(bizDate, null).stream()
                .filter(view -> item.getId().equals(view.itemId())).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("决策留痕：店主改价落 pricing_decision，查询接口联表带出 decided/finalPrice")
    void decisionIsPersistedAndJoinedBackIntoQuery() {
        LocalDate bizDate = LocalDate.of(2035, 2, 15);
        FruitItem item = ShopFixtures.item(itemMapper, "决策测试果", "测试决策类", "4.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "5", "4.00");

        SuggestionGenerateRequest generate = new SuggestionGenerateRequest();
        generate.setBizDate(bizDate);
        generate.setStrategy("RULE");
        pricingService.generate(generate);
        SuggestionView suggestion = mine(bizDate, "RULE", item.getId()).get(0);

        DecisionRequest request = new DecisionRequest();
        request.setSuggestionId(suggestion.id());
        // 恰好等于最低折扣线（6.00 ÷ 10.00 = 0.60）→ 边界允许通过
        request.setFinalPrice(new BigDecimal("6.00"));
        request.setReason("熟客价，抹零到底");
        DecisionResponse response = pricingService.decide(request);

        assertThat(response.decisionId()).isNotNull();
        assertThat(response.suggestedPrice()).isEqualByComparingTo("7.00");
        assertThat(response.finalPrice()).isEqualByComparingTo("6.00");
        assertThat(response.accepted()).isFalse();
        assertThat(response.changed()).isTrue();
        assertThat(response.operator()).isEqualTo("店主");

        PricingDecision stored = decisionMapper.selectById(response.decisionId());
        assertThat(stored.getSuggestionId()).isEqualTo(suggestion.id());
        assertThat(stored.getSuggestedPrice()).isEqualByComparingTo("7.00");
        assertThat(stored.getFinalPrice()).isEqualByComparingTo("6.00");
        assertThat(stored.getAccepted()).isFalse();
        assertThat(stored.getOperator()).isEqualTo("店主");
        assertThat(stored.getReason()).isEqualTo("熟客价，抹零到底");
        assertThat(stored.getDecidedAt()).isNotNull();

        SuggestionView after = mine(bizDate, "RULE", item.getId()).get(0);
        assertThat(after.decided()).isTrue();
        assertThat(after.finalPrice()).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("决策留痕：按建议价成交时 accepted 自动为 true，条数只增不改历史")
    void decisionAcceptsSuggestedPrice() {
        LocalDate bizDate = LocalDate.of(2035, 2, 16);
        FruitItem item = ShopFixtures.item(itemMapper, "采纳测试果", "测试决策类", "4.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "5", "4.00");

        SuggestionGenerateRequest generate = new SuggestionGenerateRequest();
        generate.setBizDate(bizDate);
        generate.setStrategy("RULE");
        pricingService.generate(generate);
        SuggestionView suggestion = mine(bizDate, "RULE", item.getId()).get(0);

        DecisionRequest request = new DecisionRequest();
        request.setSuggestionId(suggestion.id());

        DecisionResponse response = pricingService.decide(request);
        assertThat(response.accepted()).isTrue();
        assertThat(response.changed()).isFalse();
        assertThat(response.finalPrice()).isEqualByComparingTo(suggestion.suggestedPrice());
        assertThat(response.reason()).isEqualTo("采纳系统建议价");
    }

    @Test
    @DisplayName("非法策略/不存在的建议 → 400 或 404 人话报错")
    void invalidInputsAreRejectedWithReadableMessages() {
        SuggestionGenerateRequest bad = new SuggestionGenerateRequest();
        bad.setStrategy("AI");
        assertThatThrownBy(() -> pricingService.generate(bad))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FIXED/RULE/MODEL");

        DecisionRequest missing = new DecisionRequest();
        missing.setSuggestionId(-123L);
        assertThatThrownBy(() -> pricingService.decide(missing))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("查询接口：bizDate 不传时返回库里最新一天，不会因为没传日期就报错")
    void queryWithoutBizDateUsesLatestDate() {
        assertThat(pricingService.query(null, null)).isNotNull();
        assertThat(pricingService.query(LocalDate.of(1990, 1, 1), null)).isEmpty();
    }

    private List<SuggestionView> mine(LocalDate bizDate, String strategy, Long itemId) {
        return pricingService.query(bizDate, strategy).stream()
                .filter(view -> itemId.equals(view.itemId()))
                .collect(Collectors.toList());
    }
}
