package com.fruit.shop.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.BatchScrapRequest;
import com.fruit.shop.batch.dto.BatchScrapResponse;
import com.fruit.shop.batch.dto.NearExpiryRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.PricingService;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.sales.SalesService;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-06 报废人工登记（docs/04）：系统不自动淘汰，报废由人登记；登记后批次移出临期清单、累计报废量落地、计入策略损耗。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchScrapTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private StrategyDailyStatMapper statMapper;

    @Autowired
    private BatchService batchService;

    @Autowired
    private BatchScrapService scrapService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private SalesService salesService;

    @Test
    @DisplayName("FR-06 验收：整批登记报废 → SCRAPPED、移出临期清单、留痕、计入定价策略的当日损耗；同单品其他批次不被动")
    void wholeBatchScrapLeavesNearExpiryListAndCountsAsLoss() throws Exception {
        LocalDate bizDate = LocalDate.of(2036, 5, 10);
        FruitItem item = ShopFixtures.item(itemMapper, "报废测试桃", ShopFixtures.unique("报废类"), "3.00", "9.00", 5);
        FruitBatch expiring = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 5, "6", "3.00");
        FruitBatch fresh = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(1), 5, "8", "3.00");
        generate(bizDate, "RULE");

        JsonNode data = post("/api/batches/" + expiring.getId() + "/scrap",
                Map.of("bizDate", bizDate.toString(), "reason", "已过期", "operator", "小王")).path("data");

        assertThat(data.path("scrapQty").decimalValue()).isEqualByComparingTo("6");
        assertThat(data.path("remainQty").decimalValue()).isEqualByComparingTo("0");
        assertThat(data.path("status").asText()).isEqualTo("SCRAPPED");
        assertThat(data.path("strategy").asText()).isEqualTo("RULE");
        assertThat(data.path("statUpdated").asBoolean()).isTrue();

        FruitBatch stored = batchMapper.selectById(expiring.getId());
        assertThat(stored.getStatus()).isEqualTo("SCRAPPED");
        assertThat(stored.getScrapQty()).isEqualByComparingTo("6");
        assertThat(stored.getScrapReason()).isEqualTo("已过期");
        assertThat(stored.getScrapOperator()).isEqualTo("小王");
        assertThat(stored.getScrappedAt()).isNotNull();

        List<NearExpiryRow> near = batchService.nearExpiry(bizDate, 5);
        assertThat(near).extracting(NearExpiryRow::batchId).doesNotContain(expiring.getId()).contains(fresh.getId());

        FruitBatch untouched = batchMapper.selectById(fresh.getId());
        assertThat(untouched.getStatus()).isEqualTo("ON_SALE");
        assertThat(untouched.getRemainQty()).isEqualByComparingTo("8");
        assertThat(untouched.getScrapQty()).isEqualByComparingTo("0");

        StrategyDailyStat stat = stat(bizDate, "RULE");
        assertThat(stat.getScrapQty()).isEqualByComparingTo("6");
        assertThat(stat.getScrapRate()).as("当天只有报废没有销量").isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("部分报废：批次仍在售，损耗率 = 报废 ÷（销量 + 报废），与销售回流的统计口径一致")
    void partialScrapKeepsBatchOnSale() {
        LocalDate bizDate = LocalDate.of(2036, 5, 11);
        FruitItem item = ShopFixtures.item(itemMapper, "部分报废梨", ShopFixtures.unique("报废类"), "3.00", "9.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 5, "10", "3.00");
        generate(bizDate, "RULE");
        SalesFeedbackRequest sale = new SalesFeedbackRequest();
        sale.setBizDate(bizDate);
        sale.setBatchId(batch.getId());
        sale.setQuantity(new BigDecimal("4"));
        sale.setUnitPrice(new BigDecimal("6.30"));
        salesService.feedback(sale);

        BatchScrapResponse response = scrapService.scrap(batch.getId(), request(bizDate, "2", "磕碰"));

        assertThat(response.status()).isEqualTo("ON_SALE");
        assertThat(response.remainQty()).isEqualByComparingTo("4");
        assertThat(response.totalScrapQty()).isEqualByComparingTo("2");
        assertThat(response.message()).contains("部分报废");
        StrategyDailyStat stat = stat(bizDate, "RULE");
        assertThat(stat.getSoldQty()).isEqualByComparingTo("4");
        assertThat(stat.getScrapQty()).isEqualByComparingTo("2");
        assertThat(stat.getScrapRate()).isEqualByComparingTo("0.3333");

        // 报废之后再卖：销售回流重算的损耗率也要把已登记的报废算进去（2 ÷ (6 + 2)）
        sale.setQuantity(new BigDecimal("2"));
        salesService.feedback(sale);
        StrategyDailyStat after = stat(bizDate, "RULE");
        assertThat(after.getSoldQty()).isEqualByComparingTo("6");
        assertThat(after.getScrapRate()).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("拦截：超过现存、没写原因、已无现存、批次不存在；被拒的请求不改任何数据")
    void rejectsInvalidRegistrations() throws Exception {
        LocalDate bizDate = LocalDate.of(2036, 5, 12);
        FruitItem item = ShopFixtures.item(itemMapper, "报废拦截果", ShopFixtures.unique("报废类"), "3.00", "9.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 5, "3", "3.00");

        assertThatThrownBy(() -> scrapService.scrap(batch.getId(), request(bizDate, "3.01", "腐烂")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超过");
        assertThatThrownBy(() -> scrapService.scrap(batch.getId(), request(bizDate, "1", "  ")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原因");
        assertThatThrownBy(() -> scrapService.scrap(-1L, request(bizDate, "1", "腐烂")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("批次不存在");
        ResponseEntity<String> noReason = restTemplate.postForEntity(url("/api/batches/" + batch.getId() + "/scrap"),
                jsonEntity(Map.of("bizDate", bizDate.toString())), String.class);
        assertThat(noReason.getStatusCode().value()).isEqualTo(400);

        FruitBatch stored = batchMapper.selectById(batch.getId());
        assertThat(stored.getRemainQty()).isEqualByComparingTo("3");
        assertThat(stored.getScrapQty()).isEqualByComparingTo("0");
        assertThat(stored.getScrapReason()).isNull();

        scrapService.scrap(batch.getId(), request(bizDate, null, "腐烂"));
        assertThatThrownBy(() -> scrapService.scrap(batch.getId(), request(bizDate, null, "腐烂")))
                .as("整批报废后再登记：已无现存").isInstanceOf(BusinessException.class).hasMessageContaining("没有现存");
        assertThat(batchMapper.selectById(batch.getId()).getScrapQty()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("该单品从没生成过定价建议：照样登记报废，但不编造策略归属、不写策略统计")
    void scrapWithoutAnySuggestionIsNotAttributed() {
        LocalDate bizDate = LocalDate.of(2036, 5, 13);
        FruitItem item = ShopFixtures.item(itemMapper, "无建议报废果", ShopFixtures.unique("报废类"), "3.00", "9.00", 30);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 30, "5", "3.00");
        long before = statMapper.selectCount(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate));

        BatchScrapResponse response = scrapService.scrap(batch.getId(), request(bizDate, null, "运输压坏"));

        assertThat(response.status()).isEqualTo("SCRAPPED");
        assertThat(response.statUpdated()).isFalse();
        assertThat(response.strategy()).isNull();
        assertThat(response.message()).contains("未计入策略统计");
        assertThat(statMapper.selectCount(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate))).isEqualTo(before);
    }

    private static BatchScrapRequest request(LocalDate bizDate, String qty, String reason) {
        BatchScrapRequest request = new BatchScrapRequest();
        request.setBizDate(bizDate);
        request.setQty(qty == null ? null : new BigDecimal(qty));
        request.setReason(reason);
        return request;
    }

    private void generate(LocalDate bizDate, String strategy) {
        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy(strategy);
        pricingService.generate(request);
    }

    private StrategyDailyStat stat(LocalDate bizDate, String strategy) {
        return statMapper.selectOne(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate)
                .eq(StrategyDailyStat::getStrategy, strategy)
                .last("LIMIT 1"));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(url(path), jsonEntity(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("body=%s", response.getBody()).isTrue();
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.path("code").asInt()).isZero();
        return json;
    }
}
