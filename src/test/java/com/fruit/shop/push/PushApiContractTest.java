package com.fruit.shop.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.support.ShopFixtures;
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
 * 推送 v2 端到端契约测试（走真实 HTTP）：预览建计划 → 发送写日志 → 标记打开 → 到店成交归因 → 效果统计。
 *
 * <p>docs/07 第 1 节 C 的四件事在这条链上一次跑通：
 * 计划（push_task）与效果（push_log）拆表、打开率、3 小时同单品"推送后成交"、幂等重跑归因。
 * 原有 12 条接口的路径与字段名由 {@code support/ApiContractTest} 锁定，这里只用新增接口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushApiContractTest {

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
    private MemberMapper memberMapper;

    @Autowired
    private MemberPrefMapper memberPrefMapper;

    @Autowired
    private TrafficHourlyMapper trafficMapper;

    @Test
    @DisplayName("端到端：preview 建计划 → send 写日志 → open 打开 → feedback 归因 → stats 出打开率/转化率")
    void pushPlanToConversionChain() {
        LocalDate bizDate = LocalDate.of(2038, 5, 1);
        FruitItem item = ShopFixtures.item(itemMapper, "端到端契约果", "测试端到端类", "6.00", "16.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "10", "6.00");
        Member member = ShopFixtures.member(memberMapper, "端到端会员", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "9.100", 7);
        // 当日高峰 10:00 → 计划时间应为高峰前 1 小时（09:00）
        ShopFixtures.traffic(trafficMapper, bizDate, 10, 120, 90, true);

        // 1) 预览 = 建计划
        JsonNode preview = post("/api/push/preview", json(
                "itemId", item.getId(),
                "discountRate", 0.6,
                "bizDate", bizDate.toString(),
                "sendAt", bizDate + " 09:00:00"));
        long taskId = preview.path("data").path("taskId").asLong();
        assertThat(taskId).isPositive();
        assertThat(preview.path("data").path("planStatus").asText()).isEqualTo("PLANNED");
        assertThat(preview.path("data").path("planTime").asText()).isEqualTo(bizDate + " 09:00:00");
        assertThat(preview.path("data").path("reason").asText()).contains("高峰 10:00").contains("提前 1 小时");
        assertThat(preview.path("data").path("audienceRule").asText()).contains("规则 6");
        assertThat(preview.path("data").path("targetCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(preview.path("data").path("candidateMemberIds").isArray()).isTrue();

        JsonNode tasks = get("/api/push/tasks?bizDate=" + bizDate + "&taskId=" + taskId);
        assertThat(tasks.path("data")).hasSize(1);
        assertThat(tasks.path("data").get(0).path("status").asText()).isEqualTo("PLANNED");
        assertThat(tasks.path("data").get(0).path("delivered").asInt()).isZero();

        // 2) 发送 = 写日志（个人 1 条 + 群发文案 1 条）
        JsonNode send = post("/api/push/send", json(
                "taskId", taskId,
                "sendAt", bizDate + " 09:30:00"));
        assertThat(send.path("data").path("sent").asInt()).isEqualTo(2);
        assertThat(send.path("data").path("sentPersonal").asInt()).isEqualTo(1);
        assertThat(send.path("data").path("sentGroup").asInt()).isEqualTo(1);
        assertThat(send.path("data").path("taskStatus").asText()).isEqualTo("SENT");
        assertThat(send.path("data").path("messageIds")).hasSize(2);
        assertThat(send.path("data").path("convertWindowHours").asInt()).isEqualTo(3);
        long logId = send.path("data").path("sentItems").get(0).path("logId").asLong();
        assertThat(logId).isPositive();

        // 3) 标记打开（幂等）
        JsonNode opened = post("/api/push/open", json("logId", logId, "openedAt", bizDate + " 09:40:00"));
        assertThat(opened.path("data").path("opened").asBoolean()).isTrue();
        assertThat(opened.path("data").path("alreadyOpened").asBoolean()).isFalse();
        JsonNode reopened = post("/api/push/open", json("logId", logId));
        assertThat(reopened.path("data").path("alreadyOpened").asBoolean()).isTrue();

        // 4) 到店成交 → 自动归因（3 小时内同单品）
        JsonNode feedback = post("/api/sales/feedback", json(
                "bizDate", bizDate.toString(),
                "itemId", item.getId(),
                "batchId", batch.getId(),
                "quantity", 2,
                "unitPrice", 9.6,
                "memberId", member.getId(),
                "saleTime", bizDate + " 10:30:00"));
        assertThat(feedback.path("data").path("memberId").asLong()).isEqualTo(member.getId());
        assertThat(feedback.path("data").path("convertedByPush").asBoolean()).isTrue();
        assertThat(feedback.path("data").path("pushLogId").asLong()).isEqualTo(logId);
        assertThat(feedback.path("data").path("convertWindowHours").asInt()).isEqualTo(3);

        // 5) 统计：分子分母都能核对
        JsonNode stats = get("/api/push/stats?bizDate=" + bizDate + "&taskId=" + taskId);
        assertThat(stats.path("data").path("delivered").asInt()).isEqualTo(1);
        assertThat(stats.path("data").path("opened").asInt()).isEqualTo(1);
        assertThat(stats.path("data").path("converted").asInt()).isEqualTo(1);
        assertThat(stats.path("data").path("groupRows").asInt()).isEqualTo(1);
        assertThat(stats.path("data").path("openRate").asDouble()).isEqualTo(1.0);
        assertThat(stats.path("data").path("convertRate").asDouble()).isEqualTo(1.0);
        assertThat(stats.path("data").path("note").asText()).contains("送达数");
        assertThat(stats.path("data").path("byTask").get(0).path("status").asText()).isEqualTo("SENT");

        // 6) 幂等重跑归因：第二次不再新增
        JsonNode attribute = post("/api/push/attribute", json("bizDate", bizDate.toString()));
        assertThat(attribute.path("data").path("newlyAttributed").asInt()).isZero();
        assertThat(attribute.path("data").path("alreadyAttributed").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 工具

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

    private Map<String, Object> json(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
