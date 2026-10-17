package com.fruit.shop.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.sales.SalesService;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 销售回流录入页"选批次"用的 {@code GET /api/batches/in-stock}（Issue #4）：
 * 只列有库存的在售批次，顺序与回流不指定批次时的 FIFO 自动挑选一致（第一行就是会被扣的那批）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InStockBatchTest {

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
    private SalesService salesService;

    @Test
    @DisplayName("只列有库存的在售批次，按到期日、再按 id 排序；第一行就是回流不指定批次时自动扣的那批")
    void listsInStockBatchesInFifoOrder() throws Exception {
        LocalDate bizDate = LocalDate.of(2036, 3, 10);
        FruitItem item = ShopFixtures.item(itemMapper, "选批次梨", ShopFixtures.unique("选批次类"), "3.00", "8.00", 5);
        FruitBatch later = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(1), 5, "10", "3.00");
        FruitBatch sameDayA = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 4, "6", "3.00");
        FruitBatch sameDayB = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 3, "7", "3.00");
        FruitBatch soldOut = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 3, "5", "3.00");
        soldOut.setRemainQty(BigDecimal.ZERO);
        soldOut.setStatus("SOLD_OUT");
        batchMapper.updateById(soldOut);

        JsonNode data = get("/api/batches/in-stock?itemId=" + item.getId() + "&bizDate=" + bizDate).path("data");
        List<Long> ids = new ArrayList<>();
        data.forEach(n -> ids.add(n.path("batchId").asLong()));

        // sameDayA 与 sameDayB 同一天到期（bizDate+1），按 id 先后；later 最晚到期；售罄的不出现
        assertThat(ids).containsExactly(sameDayA.getId(), sameDayB.getId(), later.getId());
        JsonNode first = data.get(0);
        assertThat(first.path("daysLeft").asInt()).isEqualTo(1);
        assertThat(first.path("remainQty").decimalValue()).isEqualByComparingTo("6");
        assertThat(first.path("basePrice").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(first.path("itemName").asText()).isEqualTo("选批次梨");
        assertThat(first.has("batchNo") && first.has("expireDate") && first.has("unit")).isTrue();

        SalesFeedbackRequest sale = new SalesFeedbackRequest();
        sale.setBizDate(bizDate);
        sale.setItemId(item.getId());
        sale.setQuantity(BigDecimal.ONE);
        sale.setUnitPrice(new BigDecimal("8.00"));
        SalesFeedbackResponse res = salesService.feedback(sale);
        assertThat(res.batchAutoPicked()).isTrue();
        assertThat(res.batchId()).as("自动挑选与列表第一行一致").isEqualTo(sameDayA.getId());
    }

    @Test
    @DisplayName("缺 itemId 返回 400；单品不存在返回 404")
    void rejectsMissingOrUnknownItem() throws Exception {
        ResponseEntity<String> missing = restTemplate.getForEntity(url("/api/batches/in-stock"), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> unknown = restTemplate.getForEntity(url("/api/batches/in-stock?itemId=987654321"), String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(objectMapper.readTree(unknown.getBody()).path("message").asText()).contains("单品不存在");
    }

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("body=%s", response.getBody()).isTrue();
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asInt()).isZero();
        return body;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
