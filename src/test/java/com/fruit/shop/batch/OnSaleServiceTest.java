package com.fruit.shop.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.OnSaleRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.PricingService;
import com.fruit.shop.pricing.dto.DecisionRequest;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.dto.SuggestionView;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 顾客端"当日特价"（{@code GET /api/batches/on-sale}）验收。
 *
 * <p>三条硬规则各有断言：只有店主确认过的批次才出现、价格取 {@code pricing_decision.final_price}
 * （改价后顾客看到改后价）、售罄/下架批次不出现；接口只读、不含任何会员字段。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnSaleServiceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OnSaleService onSaleService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Test
    @DisplayName("只返回店主已确认的批次；改价后顾客看到的是 pricing_decision.final_price")
    void onlyDecidedBatchesAreVisibleWithOwnerFinalPrice() {
        LocalDate bizDate = LocalDate.of(2035, 12, 31);
        String category = ShopFixtures.unique("顾客端类");
        FruitItem accepted = ShopFixtures.item(itemMapper, "顾客端采纳果", category, "4.00", "10.00", 5);
        FruitItem undecided = ShopFixtures.item(itemMapper, "顾客端未确认果", category, "4.00", "10.00", 5);
        FruitItem repriced = ShopFixtures.item(itemMapper, "顾客端改价果", category, "4.00", "10.00", 5);
        FruitBatch acceptedBatch = ShopFixtures.batch(batchMapper, accepted.getId(), bizDate.minusDays(3), 5, "6", "4.00");
        FruitBatch undecidedBatch = ShopFixtures.batch(batchMapper, undecided.getId(), bizDate.minusDays(3), 5, "6", "4.00");
        FruitBatch repricedBatch = ShopFixtures.batch(batchMapper, repriced.getId(), bizDate.minusDays(3), 5, "6", "4.00");

        SuggestionGenerateRequest generate = new SuggestionGenerateRequest();
        generate.setBizDate(bizDate);
        generate.setStrategy("RULE");
        pricingService.generate(generate);
        Map<Long, SuggestionView> byBatch = pricingService.query(bizDate, "RULE").stream()
                .collect(Collectors.toMap(SuggestionView::batchId, view -> view));

        // 采纳建议价（7.00）
        Long acceptedSuggestion = byBatch.get(acceptedBatch.getId()).id();
        DecisionRequest accept = new DecisionRequest();
        accept.setSuggestionId(acceptedSuggestion);
        pricingService.decide(accept);

        // 店主改价到 6.00（恰好是 0.60 最低折扣线，允许）
        DecisionRequest reprice = new DecisionRequest();
        reprice.setSuggestionId(byBatch.get(repricedBatch.getId()).id());
        reprice.setFinalPrice(new BigDecimal("6.00"));
        reprice.setReason("当天现摘，直接让到 6 折");
        pricingService.decide(reprice);

        // 没有决策的批次（undecidedBatch）不出现
        List<OnSaleRow> rows = onSaleService.onSale(bizDate);
        Map<Long, OnSaleRow> mine = new HashMap<>();
        rows.stream().filter(row -> Set.of(acceptedBatch.getId(), undecidedBatch.getId(), repricedBatch.getId())
                .contains(row.batchId())).forEach(row -> mine.put(row.batchId(), row));

        assertThat(mine).doesNotContainKey(undecidedBatch.getId());
        assertThat(mine).containsKeys(acceptedBatch.getId(), repricedBatch.getId());

        OnSaleRow acceptedRow = mine.get(acceptedBatch.getId());
        assertThat(acceptedRow.finalPrice()).isEqualByComparingTo("7.00");
        assertThat(acceptedRow.originalPrice()).isEqualByComparingTo("10.00");
        assertThat(acceptedRow.discountRate()).isEqualByComparingTo("0.7000");
        assertThat(acceptedRow.discountLabel()).isEqualTo("7折");
        assertThat(acceptedRow.itemName()).isEqualTo("顾客端采纳果");
        assertThat(acceptedRow.daysLeft()).isEqualTo(2);
        assertThat(acceptedRow.freshness()).isEqualByComparingTo("0.4");
        assertThat(acceptedRow.decisionAt()).isNotNull();
        assertThat(acceptedRow.remainQty()).isEqualByComparingTo("6");

        OnSaleRow repricedRow = mine.get(repricedBatch.getId());
        assertThat(repricedRow.finalPrice()).as("顾客看到的是店主改后的价").isEqualByComparingTo("6.00");
        assertThat(repricedRow.discountLabel()).isEqualTo("6折");
        assertThat(repricedRow.decisionId()).isNotNull();

        // 缺省日期：取"最近有店主决策的一天"，本测试用的 2035-12-31 是最新的一天
        assertThat(onSaleService.latestDecidedDate()).isEqualTo(bizDate);
        assertThat(onSaleService.onSale(null)).extracting(OnSaleRow::batchId).contains(acceptedBatch.getId());
    }

    @Test
    @DisplayName("售罄/下架批次即使有决策也不出现")
    void soldOutBatchesAreHidden() {
        LocalDate bizDate = LocalDate.of(2035, 12, 30);
        FruitItem item = ShopFixtures.item(itemMapper, "顾客端售罄果", ShopFixtures.unique("售罄类"),
                "4.00", "10.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");

        SuggestionGenerateRequest generate = new SuggestionGenerateRequest();
        generate.setBizDate(bizDate);
        generate.setStrategy("RULE");
        pricingService.generate(generate);
        SuggestionView view = pricingService.query(bizDate, "RULE").stream()
                .filter(row -> batch.getId().equals(row.batchId())).findFirst().orElseThrow();
        DecisionRequest decision = new DecisionRequest();
        decision.setSuggestionId(view.id());
        pricingService.decide(decision);

        assertThat(onSaleService.onSale(bizDate)).extracting(OnSaleRow::batchId).contains(batch.getId());

        // 卖光：remain_qty=0 + SOLD_OUT（销售回流接口干的事，这里直接落库模拟）
        FruitBatch soldOut = new FruitBatch();
        soldOut.setId(batch.getId());
        soldOut.setRemainQty(BigDecimal.ZERO);
        soldOut.setStatus("SOLD_OUT");
        batchMapper.updateById(soldOut);

        assertThat(onSaleService.onSale(bizDate)).extracting(OnSaleRow::batchId).doesNotContain(batch.getId());
    }

    @Test
    @DisplayName("HTTP 契约：字段齐全、价格是店主最终价、不含任何会员/个人信息字段")
    void httpContractExposesOwnerConfirmedPriceOnly() throws Exception {
        LocalDate bizDate = LocalDate.of(2035, 12, 29);
        FruitItem item = ShopFixtures.item(itemMapper, "顾客端接口果", ShopFixtures.unique("顾客接口类"),
                "4.00", "10.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");

        SuggestionGenerateRequest generate = new SuggestionGenerateRequest();
        generate.setBizDate(bizDate);
        generate.setStrategy("RULE");
        pricingService.generate(generate);
        SuggestionView view = pricingService.query(bizDate, "RULE").stream()
                .filter(row -> batch.getId().equals(row.batchId())).findFirst().orElseThrow();
        DecisionRequest decision = new DecisionRequest();
        decision.setSuggestionId(view.id());
        decision.setFinalPrice(new BigDecimal("6.50"));
        pricingService.decide(decision);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/batches/on-sale?bizDate=" + bizDate, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET /api/batches/on-sale 期望 2xx，实际 %s，body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asInt()).isZero();

        JsonNode mine = null;
        for (JsonNode node : body.path("data")) {
            if (node.path("batchId").asLong() == batch.getId()) {
                mine = node;
            }
        }
        assertThat(mine).as("店主确认过的批次应出现在 H5 特价页").isNotNull();
        assertThat(mine.path("finalPrice").asDouble()).isEqualTo(6.5);
        assertThat(mine.path("originalPrice").asDouble()).isEqualTo(10.0);
        assertThat(mine.path("discountRate").asDouble()).isEqualTo(0.65);
        assertThat(mine.path("discountLabel").asText()).isEqualTo("6.5折");
        assertThat(mine.path("itemName").asText()).isEqualTo("顾客端接口果");
        assertThat(mine.path("unit").asText()).isEqualTo("份");
        assertThat(mine.path("remainQty").asDouble()).isEqualTo(6.0);
        assertThat(mine.path("daysLeft").asInt()).isEqualTo(2);
        assertThat(mine.path("expireDate").asText()).isEqualTo(bizDate.plusDays(2).toString());
        assertThat(mine.path("atRisk").isBoolean()).isTrue();
        assertThat(mine.path("decisionAt").asText()).contains(" ").doesNotContain("T");

        // 不返回任何会员/个人信息字段
        Set<String> fields = new HashSet<>();
        for (Iterator<String> it = mine.fieldNames(); it.hasNext(); ) {
            fields.add(it.next());
        }
        assertThat(fields).noneMatch(field -> field.toLowerCase().contains("member")
                || field.toLowerCase().contains("phone") || field.toLowerCase().contains("pref"));
    }
}
