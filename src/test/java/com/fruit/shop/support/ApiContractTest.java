package com.fruit.shop.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 12 个接口的契约测试：走真实 HTTP（RANDOM_PORT + TestRestTemplate），
 * 逐条核对 docs/01-技术方案.md 第 5 节的路径与字段名，以及"统一响应体 + 校验失败 code=400"。
 *
 * <p>这条测试是后端和前端之间的"契约锁"：字段改名会在这里直接挂掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private MemberPrefMapper memberPrefMapper;

    @Autowired
    private TrafficHourlyMapper trafficMapper;

    @Test
    @DisplayName("接口 1 GET /api/health：统一响应体里的 status/checks")
    void healthReturnsUnifiedBody() {
        JsonNode body = get("/api/health");

        assertThat(body.path("code").asInt()).isZero();
        assertThat(body.path("message").asText()).isEqualTo("ok");
        assertThat(body.path("data").path("application").asText()).isEqualTo("fruit-shop");
        assertThat(body.path("data").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("data").path("checks").isArray()).isTrue();
        assertThat(body.path("data").path("checks")).isNotEmpty();
    }

    @Test
    @DisplayName("接口 2/3：登记批次返回 batchId/batchNo/expireDate，临期清单字段齐全")
    void batchAndNearExpiryContract() {
        LocalDate bizDate = LocalDate.of(2035, 5, 1);
        long itemId = createItem("接口测试芒果", "测试接口类", 6.5, 16.0, 5);

        JsonNode batch = post("/api/batches", json(
                "itemId", itemId,
                "arriveDate", bizDate.minusDays(3).toString(),   // days_left = 2
                "shelfLifeDays", 5,
                "qty", 8,
                "costPrice", 6.5));
        long batchId = batch.path("data").path("batchId").asLong();
        String batchNo = batch.path("data").path("batchNo").asText();
        assertThat(batchId).isPositive();
        assertThat(batchNo).isNotBlank();
        assertThat(batch.path("data").path("expireDate").asText()).isEqualTo(bizDate.plusDays(2).toString());

        JsonNode near = get("/api/batches/near-expiry?bizDate=" + bizDate + "&days=2");
        assertThat(near.path("data").isArray()).isTrue();
        JsonNode mine = findBy(near.path("data"), "batchId", batchId);
        assertThat(mine).as("临期清单里应包含刚登记的批次").isNotNull();
        assertThat(mine.path("itemName").asText()).isEqualTo("接口测试芒果");
        assertThat(mine.path("categoryName").asText()).isEqualTo("测试接口类");
        assertThat(mine.path("unit").asText()).isEqualTo("份");
        assertThat(mine.path("batchNo").asText()).isEqualTo(batchNo);
        assertThat(mine.path("daysLeft").asInt()).isEqualTo(2);
        assertThat(mine.path("remainQty").asDouble()).isEqualTo(8.0);
        assertThat(mine.path("costPrice").asDouble()).isEqualTo(6.5);
        assertThat(mine.path("basePrice").asDouble()).isEqualTo(16.0);
        assertThat(mine.path("lossRatePct").asDouble()).isEqualTo(10.0);
        assertThat(mine.path("arriveDate").asText()).isEqualTo(bizDate.minusDays(3).toString());
        assertThat(mine.path("expireDate").asText()).isEqualTo(bizDate.plusDays(2).toString());
    }

    @Test
    @DisplayName("参数校验失败：HTTP 400 + body.code=400 + 可读 message")
    void validationFailureReturnsCode400WithReadableMessage() {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/batches", json("qty", 0));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = parse(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText())
                .startsWith("参数校验失败")
                .contains("itemId")
                .contains("qty");
    }

    @Test
    @DisplayName("接口 4/5/6：生成建议 → 查询（含 itemName/batchNo/decided）→ 店主改价留痕")
    void pricingContract() {
        LocalDate bizDate = LocalDate.of(2035, 5, 2);
        long itemId = createItem("接口定价果", "测试接口定价类", 5.0, 10.0, 5);
        createBatch(itemId, bizDate.minusDays(3), 5, 6, 5.0); // days_left = 2

        JsonNode generated = post("/api/pricing/suggestions/generate", json("bizDate", bizDate.toString(), "strategy", "RULE"));
        assertThat(generated.path("data").path("bizDate").asText()).isEqualTo(bizDate.toString());
        assertThat(generated.path("data").path("strategy").asText()).isEqualTo("RULE");
        assertThat(generated.path("data").path("generated").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode list = get("/api/pricing/suggestions?bizDate=" + bizDate + "&strategy=RULE");
        assertThat(list.path("data").isArray()).isTrue();
        JsonNode suggestion = findBy(list.path("data"), "itemId", itemId);
        assertThat(suggestion).isNotNull();
        assertThat(suggestion.path("itemName").asText()).isEqualTo("接口定价果");
        assertThat(suggestion.path("batchNo").asText()).isNotBlank();
        assertThat(suggestion.path("daysLeft").asInt()).isEqualTo(2);
        assertThat(suggestion.path("discountRate").asDouble()).isEqualTo(0.70);
        assertThat(suggestion.path("originalPrice").asDouble()).isEqualTo(10.0);
        assertThat(suggestion.path("suggestedPrice").asDouble())
                .isLessThanOrEqualTo(suggestion.path("originalPrice").asDouble());
        assertThat(suggestion.path("reason").asText()).isNotBlank();
        assertThat(suggestion.path("expectedQty").isNull()).isFalse();
        assertThat(suggestion.path("decided").asBoolean()).isFalse();
        assertThat(suggestion.path("finalPrice").isNull()).isTrue();

        JsonNode decision = post("/api/pricing/decisions", json(
                "suggestionId", suggestion.path("id").asLong(),
                "finalPrice", 6.5,
                "reason", "接口测试：店主改价"));
        assertThat(decision.path("data").path("decisionId").asLong()).isPositive();
        assertThat(decision.path("data").path("finalPrice").asDouble()).isEqualTo(6.5);
        assertThat(decision.path("data").path("accepted").asBoolean()).isFalse();
        // 时间口径：yyyy-MM-dd HH:mm:ss（不是 ISO 的 T 分隔）
        assertThat(decision.path("data").path("decidedAt").asText()).contains(" ").doesNotContain("T");

        JsonNode afterDecision = findBy(get("/api/pricing/suggestions?bizDate=" + bizDate).path("data"), "itemId", itemId);
        assertThat(afterDecision.path("decided").asBoolean()).isTrue();
        assertThat(afterDecision.path("finalPrice").asDouble()).isEqualTo(6.5);
    }

    @Test
    @DisplayName("接口 7/8：每小时客流数组 + 高峰与\"高峰前 1 小时\"的推送建议")
    void trafficContract() {
        LocalDate date = LocalDate.of(2035, 5, 3);
        ShopFixtures.traffic(trafficMapper, date, 9, 30, 20, false);
        ShopFixtures.traffic(trafficMapper, date, 10, 100, 80, true);

        JsonNode hourly = get("/api/traffic/hourly?date=" + date);
        assertThat(hourly.path("data").isArray()).isTrue();
        assertThat(hourly.path("data")).hasSize(24);
        JsonNode ten = findBy(hourly.path("data"), "hourOfDay", 10);
        assertThat(ten).isNotNull();
        assertThat(ten.path("orderCount").asInt()).isEqualTo(100);
        assertThat(ten.path("customerCount").asInt()).isEqualTo(80);
        assertThat(ten.path("isPeak").asBoolean()).isTrue();

        JsonNode peaks = get("/api/traffic/peaks?date=" + date);
        assertThat(peaks.path("data").path("date").asText()).isEqualTo(date.toString());
        assertThat(peaks.path("data").path("peakHours").get(0).asInt()).isEqualTo(10);
        assertThat(peaks.path("data").path("suggestPushHours").get(0).asInt()).isEqualTo(9);
        assertThat(peaks.path("data").path("note").asText()).isNotBlank();
    }

    @Test
    @DisplayName("接口 9/10：文案预览（title/content/targetCount/targets）与发送（sent/跳过条数/messageIds）")
    void pushContract() {
        LocalDate bizDate = LocalDate.of(2035, 5, 4);
        long itemId = createItem("接口推送果", "测试接口推送类", 9.0, 24.0, 4);
        Member member = ShopFixtures.member(memberMapper, "接口会员", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), "测试接口推送类", "7.000", 3);

        JsonNode preview = post("/api/push/preview", json("itemId", itemId, "discountRate", 0.6, "targetCount", 5));
        assertThat(preview.path("data").path("title").asText()).isNotBlank();
        assertThat(preview.path("data").path("content").asText()).contains("6折");
        assertThat(preview.path("data").path("targetCount").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode target = preview.path("data").path("targets").get(0);
        assertThat(target.path("memberId").asLong()).isEqualTo(member.getId());
        assertThat(target.path("nickname").asText()).isEqualTo("接口会员");
        assertThat(target.path("categoryName").asText()).isEqualTo("测试接口推送类");
        assertThat(target.path("reason").asText()).isNotBlank();

        JsonNode send = post("/api/push/send", json(
                "bizDate", bizDate.toString(),
                "itemId", itemId,
                "discountRate", 0.6,
                "memberIds", List.of(member.getId()),
                "sendAt", bizDate + " 09:30:00"));
        assertThat(send.path("data").path("sent").asInt()).isEqualTo(1);
        assertThat(send.path("data").path("skippedByLimit").asInt()).isZero();
        assertThat(send.path("data").path("skippedByQuietHours").asInt()).isZero();
        assertThat(send.path("data").path("messageIds")).hasSize(1);
        // 时间口径：yyyy-MM-dd HH:mm:ss
        assertThat(send.path("data").path("sendAt").asText()).isEqualTo(bizDate + " 09:30:00");
    }

    @Test
    @DisplayName("接口 11/12：销售回流把 remain_qty 扣到 0，效果看板给出损耗率/毛利/营收/按日趋势")
    void salesContract() {
        LocalDate bizDate = LocalDate.of(2035, 5, 5);
        long itemId = createItem("接口回流果", "测试接口回流类", 5.0, 12.0, 5);
        long batchId = createBatch(itemId, bizDate.minusDays(3), 5, 4, 5.0); // days_left = 2，现存 4
        post("/api/pricing/suggestions/generate", json("bizDate", bizDate.toString(), "strategy", "RULE"));

        JsonNode feedback = post("/api/sales/feedback", json(
                "bizDate", bizDate.toString(),
                "itemId", itemId,
                "batchId", batchId,
                "quantity", 4,
                "unitPrice", 9.0));
        assertThat(feedback.path("data").path("remainQty").asDouble()).isEqualTo(0.0);
        assertThat(feedback.path("data").path("batchStatus").asText()).isEqualTo("SOLD_OUT");

        JsonNode effect = get("/api/sales/effect?from=" + bizDate + "&to=" + bizDate + "&strategy=RULE");
        assertThat(effect.path("data").path("revenue").asDouble()).isEqualTo(36.0);
        assertThat(effect.path("data").path("cost").asDouble()).isEqualTo(20.0);
        assertThat(effect.path("data").path("soldQty").asDouble()).isEqualTo(4.0);
        assertThat(effect.path("data").path("grossProfit").asDouble()).isEqualTo(16.0);
        assertThat(effect.path("data").path("scrapQty").asDouble()).isEqualTo(0.0);
        assertThat(effect.path("data").path("scrapRate").asDouble()).isEqualTo(0.0);
        assertThat(effect.path("data").path("daily")).hasSize(1);
        assertThat(effect.path("data").path("daily").get(0).path("bizDate").asText()).isEqualTo(bizDate.toString());
        assertThat(effect.path("data").path("daily").get(0).path("revenue").asDouble()).isEqualTo(36.0);
        assertThat(effect.path("data").path("daily").get(0).path("strategy").asText()).isEqualTo("RULE");
    }

    @Test
    @DisplayName("未知路径/无数据查询不会 500：效果看板查 1990 年返回 0")
    void emptyRangesAreNotErrors() {
        JsonNode effect = get("/api/sales/effect?from=1990-01-01&to=1990-01-07");
        assertThat(effect.path("code").asInt()).isZero();
        assertThat(effect.path("data").path("revenue").asDouble()).isEqualTo(0.0);

        JsonNode near = get("/api/batches/near-expiry?bizDate=1990-01-01&days=2");
        assertThat(near.path("code").asInt()).isZero();
        assertThat(near.path("data")).isEmpty();
    }

    @Test
    @DisplayName("未知路径返回 404 + 统一响应体（不会伪装成 500）")
    void unknownPathReturnsUnifiedNotFound() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/not-exist", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = parse(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(404);
        assertThat(body.path("message").asText()).contains("接口不存在");
    }

    // ------------------------------------------------------------------ 工具

    private long createItem(String name, String category, double costPrice, double basePrice, int shelfLifeDays) {
        JsonNode created = post("/api/items", json(
                "itemCode", ShopFixtures.unique("API"),
                "itemName", name,
                "categoryName", category,
                "unit", "份",
                "costPrice", costPrice,
                "basePrice", basePrice,
                "shelfLifeDays", shelfLifeDays,
                "lossRatePct", 10.0));
        return created.path("data").path("id").asLong();
    }

    private long createBatch(long itemId, LocalDate arriveDate, int shelfLifeDays, double qty, double costPrice) {
        JsonNode created = post("/api/batches", json(
                "itemId", itemId,
                "arriveDate", arriveDate.toString(),
                "shelfLifeDays", shelfLifeDays,
                "qty", qty,
                "costPrice", costPrice));
        return created.path("data").path("batchId").asLong();
    }

    private JsonNode get(String path) {
        ResponseEntity<String> response = exchange(HttpMethod.GET, path, null);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s 期望 2xx，实际 %s，body=%s", path, response.getStatusCode(), response.getBody()).isTrue();
        JsonNode node = parse(response.getBody());
        assertThat(node.path("code").asInt()).as("GET %s 业务 code 应为 0，body=%s", path, response.getBody()).isZero();
        return node;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        ResponseEntity<String> response = exchange(HttpMethod.POST, path, body);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("POST %s 期望 2xx，实际 %s，body=%s", path, response.getStatusCode(), response.getBody()).isTrue();
        JsonNode node = parse(response.getBody());
        assertThat(node.path("code").asInt()).as("POST %s 业务 code 应为 0，body=%s", path, response.getBody()).isZero();
        return node;
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange("http://localhost:" + port + path, method, entity, String.class);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON：" + body, e);
        }
    }

    /** 在数组里按字段值找元素；找不到返回 null。 */
    private JsonNode findBy(JsonNode array, String field, long value) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode node : array) {
            if (node.path(field).asLong() == value) {
                return node;
            }
        }
        return null;
    }

    private Map<String, Object> json(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
