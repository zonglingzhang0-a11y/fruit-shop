package com.fruit.shop.pricing;

import com.fruit.shop.batch.OnSaleService;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.OnSaleRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
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
import java.util.HashMap;
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
    private OnSaleService onSaleService;

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
        // 三个单品各一批（一个单品一天只挂一个价，所以分开放）；都一份没卖（库存压力 3 分），保质期 8 天
        Map<Integer, FruitItem> itemByDays = new HashMap<>();
        for (int daysLeft = 0; daysLeft <= 2; daysLeft++) {
            FruitItem item = ShopFixtures.item(itemMapper, "规则测试柑橘" + daysLeft, "测试规则类", "4.00", "10.00", 8);
            ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(8 - daysLeft), 8, "5", "4.00");
            itemByDays.put(daysLeft, item);
        }

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");
        SuggestionGenerateResponse response = pricingService.generate(request);

        assertThat(response.bizDate()).isEqualTo(bizDate);
        assertThat(response.strategy()).isEqualTo("RULE");
        assertThat(response.generated()).isGreaterThanOrEqualTo(3);

        Map<Integer, SuggestionView> byDaysLeft = new HashMap<>();
        itemByDays.forEach((daysLeft, item) -> {
            List<SuggestionView> rows = mine(bizDate, "RULE", item.getId());
            assertThat(rows).hasSize(1);
            byDaysLeft.put(daysLeft, rows.get(0));
        });
        // days_left=0：紧迫度 4 + 压力 3 + 剩余寿命 0% 加 1 = 8 分 → 5 折，被最低折扣线 0.60 拦住（docs/07 第 1 节 B）→ 挂 6 折并留痕
        assertThat(byDaysLeft.get(0).discountRate()).isEqualByComparingTo("0.60");
        assertThat(byDaysLeft.get(0).priceFloorHit()).isTrue();
        assertThat(byDaysLeft.get(0).rawDiscountRate()).isEqualByComparingTo("0.50");
        assertThat(byDaysLeft.get(0).reason()).contains("最低折扣线");
        // days_left=1：3 + 3 + 剩余寿命 1/8 ≤ 20% 加 1 = 7 分 → 5 折，同样被底线抬到 6 折
        assertThat(byDaysLeft.get(1).discountRate()).isEqualByComparingTo("0.60");
        assertThat(byDaysLeft.get(1).priceFloorHit()).isTrue();
        assertThat(byDaysLeft.get(1).rawDiscountRate()).isEqualByComparingTo("0.50");
        // days_left=2：2 + 3 = 5 分（剩余寿命 25% 不加分）→ 7 折
        assertThat(byDaysLeft.get(2).discountRate()).isEqualByComparingTo("0.70");
        assertThat(byDaysLeft.get(2).priceFloorHit()).isFalse();

        for (SuggestionView view : byDaysLeft.values()) {
            assertThat(PricingPolicy.isCandidate(view.discountRate()))
                    .as("折扣必须是候选档之一，实际 %s", view.discountRate()).isTrue();
            assertThat(view.discountRate()).isLessThanOrEqualTo(BigDecimal.ONE);
            assertThat(view.suggestedPrice()).isLessThanOrEqualTo(view.originalPrice());
            assertThat(view.suggestedPrice()).isEqualByComparingTo(view.originalPrice()
                    .multiply(view.discountRate()).setScale(2, RoundingMode.HALF_UP));
            assertThat(view.reason()).isNotBlank();
            assertThat(view.expectedQty()).isNotNull();
            assertThat(view.itemName()).startsWith("规则测试柑橘");
            assertThat(view.batchNo()).isNotBlank();
            assertThat(view.strategy()).isEqualTo("RULE");
            assertThat(view.decided()).isFalse();
            assertThat(view.finalPrice()).isNull();
        }
        assertThat(byDaysLeft.get(0).reason()).contains("到期");
    }

    @Test
    @DisplayName("一个单品一天一个价（与评测同口径）：多批次取最深的一档，只出一条建议；店主确认后 H5 也只有一行、余量是合计")
    void oneItemOnePriceAcrossBatches() {
        LocalDate bizDate = LocalDate.of(2035, 2, 16);
        FruitItem item = ShopFixtures.item(itemMapper, "多批次测试梨", "测试单品价类", "4.00", "10.00", 8);
        // 刚到 4 天的新货（规则给原价）+ 明天到期、一份没卖的旧货（规则 5 折、被底线抬到 6 折）
        FruitBatch fresh = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 8, "10", "4.00");
        FruitBatch urgent = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(7), 8, "5", "4.00");

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");
        pricingService.generate(request);

        List<SuggestionView> rows = mine(bizDate, "RULE", item.getId());
        assertThat(rows).hasSize(1);
        SuggestionView view = rows.get(0);
        assertThat(view.batchId()).as("价格以折扣最深的那批为准").isEqualTo(urgent.getId());
        assertThat(view.daysLeft()).isEqualTo(1);
        assertThat(view.discountRate()).isEqualByComparingTo("0.60");
        assertThat(view.remainQty()).as("现存是该单品所有在售批次的合计").isEqualByComparingTo("15");
        assertThat(view.reason()).contains("同单品 2 个在售批次统一挂这一个价").contains(urgent.getBatchNo());

        DecisionRequest accept = new DecisionRequest();
        accept.setSuggestionId(view.id());
        pricingService.decide(accept);
        List<OnSaleRow> specials = onSaleService.onSale(bizDate).stream()
                .filter(r -> item.getId().equals(r.itemId())).toList();
        assertThat(specials).hasSize(1);
        assertThat(specials.get(0).finalPrice()).isEqualByComparingTo("6.00");
        assertThat(specials.get(0).remainQty()).isEqualByComparingTo("15");
        assertThat(specials.get(0).batchId()).as("到期日按最急的那批展示").isEqualTo(urgent.getId());
        assertThat(fresh.getId()).isNotNull();
    }

    @Test
    @DisplayName("定价范围与评测一致：剩 3 天但一份没卖的批次也会打折；只有新货的单品按原价、不出 RULE 建议")
    void rulePricesEveryInStockBatch() {
        LocalDate bizDate = LocalDate.of(2035, 2, 17);
        FruitItem pressured = ShopFixtures.item(itemMapper, "剩三天压货果", "测试范围类", "4.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, pressured.getId(), bizDate.minusDays(5), 8, "20", "4.00"); // days_left=3
        FruitItem freshOnly = ShopFixtures.item(itemMapper, "全是新货果", "测试范围类", "4.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, freshOnly.getId(), bizDate.minusDays(2), 8, "20", "4.00"); // days_left=6

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");
        pricingService.generate(request);

        // 紧迫度 1 + 库存压力 3 = 4 分 → 7 折（原来只看临期清单 ≤ 2 天时，这批不会被定价）
        List<SuggestionView> rows = mine(bizDate, "RULE", pressured.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).daysLeft()).isEqualTo(3);
        assertThat(rows.get(0).discountRate()).isEqualByComparingTo("0.70");
        assertThat(mine(bizDate, "RULE", freshOnly.getId())).as("规则算下来是原价：不打扰店主").isEmpty();

        // FIXED 是"不降价"的对照组：每个在售单品都留一条"维持原价"
        request.setStrategy("FIXED");
        pricingService.generate(request);
        assertThat(mine(bizDate, "FIXED", freshOnly.getId())).singleElement()
                .satisfies(v -> assertThat(v.discountRate()).isEqualByComparingTo("1.00"));
    }

    @Test
    @DisplayName("RULE 库存压力看还剩几成：同样剩 25 份，一份没卖的 7 折，已卖掉七成五的 9 折")
    void stockPressureFollowsRemainingShare() {
        LocalDate bizDate = LocalDate.of(2035, 2, 11);
        FruitItem untouched = ShopFixtures.item(itemMapper, "压力测试果", "测试压力类", "3.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, untouched.getId(), bizDate.minusDays(6), 8, "25", "3.00"); // days_left=2，25/25
        FruitItem selling = ShopFixtures.item(itemMapper, "卖动测试果", "测试压力类", "3.00", "10.00", 8);
        FruitBatch sold = ShopFixtures.batch(batchMapper, selling.getId(), bizDate.minusDays(6), 8, "100", "3.00");
        sold.setRemainQty(new BigDecimal("25")); // 到货 100、已卖 75
        batchMapper.updateById(sold);

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");
        pricingService.generate(request);

        // 紧迫度 2 + 压力 3（还剩 100%）= 5 分 → 7 折
        List<SuggestionView> full = mine(bizDate, "RULE", untouched.getId());
        assertThat(full).hasSize(1);
        assertThat(full.get(0).daysLeft()).isEqualTo(2);
        assertThat(full.get(0).discountRate()).isEqualByComparingTo("0.70");
        assertThat(full.get(0).reason()).contains("库存压力 3 分");
        // 紧迫度 2 + 压力 0（还剩 25%）= 2 分 → 9 折
        List<SuggestionView> rest = mine(bizDate, "RULE", selling.getId());
        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).discountRate()).isEqualByComparingTo("0.90");
        assertThat(rest.get(0).reason()).contains("还剩 25%").contains("库存压力 0 分");
    }

    @Test
    @DisplayName("FIXED：一律原价、不打折（与评测里的固定价基线同口径）")
    void fixedStrategyKeepsOriginalPrice() {
        LocalDate bizDate = LocalDate.of(2035, 2, 12);
        FruitItem item = ShopFixtures.item(itemMapper, "固定价测试果", "测试固定类", "3.00", "10.00", 8);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(8), 8, "5", "3.00"); // days_left = 0
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "5", "3.00"); // days_left = 2

        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("FIXED");
        pricingService.generate(request);

        List<SuggestionView> mine = mine(bizDate, "FIXED", item.getId());
        assertThat(mine).as("一个单品一天一个价：两批只出一条").hasSize(1);
        assertThat(mine.get(0).remainQty()).isEqualByComparingTo("10");
        assertThat(mine).allSatisfy(view -> {
            assertThat(view.discountRate()).isEqualByComparingTo("1.00");
            assertThat(view.suggestedPrice()).isEqualByComparingTo(view.originalPrice());
            assertThat(view.priceFloorHit()).isFalse();
            assertThat(view.reason()).contains("基线").contains("原价");
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
    @DisplayName("生成是幂等的：同一天同一策略同一单品不会重复插入")
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
