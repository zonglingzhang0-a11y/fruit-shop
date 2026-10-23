package com.fruit.shop.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.pricing.strategy.FixedPricePricingStrategy;
import com.fruit.shop.pricing.strategy.PricingContext;
import com.fruit.shop.pricing.strategy.RuleBasedPricingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统与离线评测的定价规则必须是同一套：评测报告里 FIXED / RULE 的数字，就是这里的 Java 策略会给出的折扣。
 *
 * <p>夹具 {@code contract/pricing_rule_cases.json} 由 {@code python scripts/pricing_rule.py --export-contract}
 * 从评测实际调用的 {@code rule_discount} 导出（剩余天数、剩余比例、剩余寿命比例都压在边界上），
 * Python 的 {@code --selftest} 会重算并比对夹具。所以只改一边，CI 的 Java 或 Python 任务必有一个失败。
 */
class PricingRuleContractTest {

    private static JsonNode contract() throws IOException {
        try (InputStream in = PricingRuleContractTest.class.getResourceAsStream("/contract/pricing_rule_cases.json")) {
            assertThat(in).as("缺少契约夹具，请运行 python scripts/pricing_rule.py --export-contract").isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    @Test
    @DisplayName("RULE：对评测导出的每个边界用例，Java 给出的折扣（含两种最低折扣线）与 Python 完全一致")
    void ruleMatchesEvaluationForEveryCase() throws IOException {
        JsonNode contract = contract();
        JsonNode floors = contract.get("floors");
        JsonNode cases = contract.get("cases");
        assertThat(cases.size()).as("用例数").isGreaterThan(1000);

        List<String> mismatches = new ArrayList<>();
        for (JsonNode row : cases) {
            int daysLeft = row.get(0).intValue();
            BigDecimal remain = decimalOrNull(row.get(1));
            BigDecimal init = decimalOrNull(row.get(2));
            Integer shelfLife = row.get(3).isNull() ? null : row.get(3).intValue();

            BigDecimal rate = RuleBasedPricingStrategy.score(daysLeft, remain, init, shelfLife).rate();
            List<BigDecimal> actual = new ArrayList<>(List.of(rate));
            for (JsonNode floor : floors) {
                actual.add(PricingPolicy.applyFloor(rate, floor.decimalValue()).rate());
            }
            for (int i = 0; i < actual.size(); i++) {
                BigDecimal expected = row.get(4 + i).decimalValue();
                if (actual.get(i).compareTo(expected) != 0) {
                    mismatches.add(row + " 第 " + (4 + i) + " 列：Java " + actual.get(i) + "，Python " + expected);
                }
            }
        }
        assertThat(mismatches).as("与评测不一致的用例（共 %d 条）", mismatches.size()).isEmpty();
    }

    @Test
    @DisplayName("系统默认最低折扣线就是评测用的 0.60（契约里比对过这一档）")
    void defaultFloorIsCoveredByContract() throws IOException {
        List<BigDecimal> floors = new ArrayList<>();
        contract().get("floors").forEach(f -> floors.add(f.decimalValue()));
        assertThat(floors).anySatisfy(f -> assertThat(f).isEqualByComparingTo(PricingPolicy.DEFAULT_MIN_DISCOUNT_RATE));
    }

    @Test
    @DisplayName("FIXED：与评测的固定价基线一致——不论剩几天、剩多少，一律原价")
    void fixedMatchesEvaluation() throws IOException {
        BigDecimal expected = contract().get("fixed_rate").decimalValue();
        assertThat(FixedPricePricingStrategy.FIXED_RATE).isEqualByComparingTo(expected);

        FixedPricePricingStrategy fixed = new FixedPricePricingStrategy();
        FruitItem item = new FruitItem();
        item.setUnit("份");
        for (int daysLeft : new int[] {-1, 0, 1, 2, 5}) {
            for (String remain : new String[] {"1", "40"}) {
                FruitBatch batch = new FruitBatch();
                batch.setInitQty(new BigDecimal("40"));
                batch.setRemainQty(new BigDecimal(remain));
                PricingContext context = new PricingContext(item, batch, LocalDate.of(2035, 1, 1), daysLeft,
                        new BigDecimal(remain), new BigDecimal("10.00"), new BigDecimal("6.00"), "测试类");
                assertThat(fixed.propose(context).discountRate()).isEqualByComparingTo(expected);
            }
        }
    }
}
