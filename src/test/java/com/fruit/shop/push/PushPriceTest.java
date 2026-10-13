package com.fruit.shop.push;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.OnSaleService;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.OnSaleRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.pricing.PricingService;
import com.fruit.shop.pricing.domain.PricingSuggestion;
import com.fruit.shop.pricing.dto.DecisionRequest;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.mapper.PricingSuggestionMapper;
import com.fruit.shop.push.domain.PushTask;
import com.fruit.shop.push.dto.PushPreviewRequest;
import com.fruit.shop.push.dto.PushPreviewResponse;
import com.fruit.shop.push.dto.PushSendRequest;
import com.fruit.shop.push.dto.PushSendResponse;
import com.fruit.shop.push.mapper.PushTaskMapper;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推送价格口径测试（Issue #1 队友复现：店主确认 11.52 元，推送却写"现价 6.40 元"，还低于最低折扣线 7.68）。
 *
 * <p>锁定：有确认价时推送一律用确认价；没有确认价时按折扣试算但不得低于最低折扣线；
 * 发送时按最新确认价重写文案；无在售库存不发送。
 *
 * <p>业务日用 2033 年：OnSaleServiceTest 假定 2035-12-31 是全库最新的决策日，本类会落决策，日期必须早于它。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushPriceTest {

    @Autowired
    private PushService pushService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private PricingSuggestionMapper suggestionMapper;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private MemberPrefMapper memberPrefMapper;

    @Autowired
    private PushTaskMapper taskMapper;

    @Autowired
    private OnSaleService onSaleService;

    @Test
    @DisplayName("有店主确认价：预览忽略手填 0.50 折扣，文案价格 = 确认价 11.52（与 H5 一致）")
    void previewUsesConfirmedPrice() {
        LocalDate bizDate = LocalDate.of(2033, 3, 10);
        FruitItem item = ShopFixtures.item(itemMapper, "确认价柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "5.00");
        generate(bizDate);
        Long decisionId = decide(suggestionOf(bizDate, batch.getId()), "11.52");

        PushPreviewResponse preview = preview(item.getId(), bizDate, "0.50");

        assertThat(preview.priceSource()).isEqualTo(PushService.PRICE_CONFIRMED);
        assertThat(preview.priceDecisionId()).isEqualTo(decisionId);
        assertThat(preview.discountPrice()).isEqualByComparingTo("11.52");
        assertThat(preview.content()).contains("11.52").doesNotContain("6.40");
        assertThat(preview.priceNote()).contains("店主确认价").contains("已忽略");
        assertThat(taskMapper.selectById(preview.taskId()).getRelatedSuggestionId())
                .isEqualTo(suggestionOf(bizDate, batch.getId()).getId());
    }

    @Test
    @DisplayName("没有确认价：手填 0.50 被抬到最低折扣线 0.60（12.80 → 7.68），并标注未经店主确认")
    void previewWithoutDecisionRespectsFloor() {
        LocalDate bizDate = LocalDate.of(2033, 3, 11);
        FruitItem item = ShopFixtures.item(itemMapper, "未确认柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "5.00");

        PushPreviewResponse preview = preview(item.getId(), bizDate, "0.50");

        assertThat(preview.priceSource()).isEqualTo(PushService.PRICE_UNCONFIRMED);
        assertThat(preview.discountRate()).isEqualByComparingTo("0.60");
        assertThat(preview.discountPrice()).isEqualByComparingTo("7.68");
        assertThat(preview.priceNote()).contains("还没有店主确认价").contains("最低折扣线");
    }

    @Test
    @DisplayName("预览后店主才确认价格：发送时按最新确认价重写群发文案")
    void sendRewritesCopyWithLatestConfirmedPrice() {
        LocalDate bizDate = LocalDate.of(2033, 3, 12);
        FruitItem item = ShopFixtures.item(itemMapper, "后确认柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "5.00");
        Member member = ShopFixtures.member(memberMapper, "推送价会员", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "9.000", 3);
        generate(bizDate);

        PushPreviewResponse preview = preview(item.getId(), bizDate, "0.60");
        assertThat(preview.priceSource()).isEqualTo(PushService.PRICE_UNCONFIRMED);
        decide(suggestionOf(bizDate, batch.getId()), "10.24");

        PushSendResponse sent = pushService.send(send(preview.taskId(), bizDate));

        assertThat(sent.sent()).isEqualTo(2);
        assertThat(sent.sentItems()).allSatisfy(i -> assertThat(i.content()).contains("10.24"));
        PushTask task = taskMapper.selectById(preview.taskId());
        assertThat(task.getStatus()).isEqualTo(PushTask.STATUS_SENT);
        assertThat(task.getContent()).contains("10.24");
        assertThat(sent.note()).contains("店主确认价");
    }

    @Test
    @DisplayName("发送前复核库存：预览后批次卖空，发送被拒、计划保持 PLANNED")
    void sendRefusesWhenSoldOut() {
        LocalDate bizDate = LocalDate.of(2033, 3, 13);
        FruitItem item = ShopFixtures.item(itemMapper, "卖空柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "5.00");
        PushPreviewResponse preview = preview(item.getId(), bizDate, "0.80");

        batch.setRemainQty(BigDecimal.ZERO);
        batch.setStatus("SOLD_OUT");
        batchMapper.updateById(batch);
        PushSendResponse sent = pushService.send(send(preview.taskId(), bizDate));

        assertThat(sent.sent()).isZero();
        assertThat(sent.skipped()).anySatisfy(s -> assertThat(s.reason()).contains("没有在售库存"));
        assertThat(taskMapper.selectById(preview.taskId()).getStatus()).isEqualTo(PushTask.STATUS_PLANNED);
    }

    @Test
    @DisplayName("确认价不在候选档上（12.80 → 11.00）：折扣文案写真实价格比 8.6折，与 H5 一致，不写成 8折")
    void confirmedOffStepPriceUsesRealRatioLabel() {
        LocalDate bizDate = LocalDate.of(2033, 3, 14);
        FruitItem item = ShopFixtures.item(itemMapper, "非整档柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "5.00");
        generate(bizDate);
        decide(suggestionOf(bizDate, batch.getId()), "11.00");

        PushPreviewResponse preview = preview(item.getId(), bizDate, "0.80");

        assertThat(preview.discountPrice()).isEqualByComparingTo("11.00");
        assertThat(preview.discountLabel()).isEqualTo("8.6折");
        assertThat(preview.title() + preview.content()).contains("8.6折").doesNotContain("8折清");
        OnSaleRow h5 = onSaleService.onSale(bizDate).stream()
                .filter(r -> r.batchId().equals(batch.getId())).findFirst().orElseThrow();
        assertThat(preview.discountLabel()).isEqualTo(h5.discountLabel());
    }

    @Test
    @DisplayName("没有确认价、预览 0.90，发送只传 taskId：沿用预览折扣（11.52），不退回规则默认档")
    void sendWithOnlyTaskIdKeepsPreviewedDiscount() {
        LocalDate bizDate = LocalDate.of(2033, 3, 15);
        FruitItem item = ShopFixtures.item(itemMapper, "只传计划柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "5.00");
        Member member = ShopFixtures.member(memberMapper, "只传计划会员", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "9.000", 3);

        PushPreviewResponse preview = preview(item.getId(), bizDate, "0.90");
        assertThat(preview.discountPrice()).isEqualByComparingTo("11.52");
        PushSendResponse sent = pushService.send(send(preview.taskId(), bizDate));

        assertThat(sent.sent()).isEqualTo(2);
        assertThat(sent.sentItems()).allSatisfy(i -> assertThat(i.content()).contains("11.52"));
        assertThat(taskMapper.selectById(preview.taskId()).getContent()).contains("11.52");
    }

    @Test
    @DisplayName("多个批次都有确认价：推最急（最早到期）的那批，与店主先确认哪个无关")
    void multipleConfirmedBatchesPushTheMostUrgentOne() {
        LocalDate bizDate = LocalDate.of(2033, 3, 16);
        FruitItem item = ShopFixtures.item(itemMapper, "多批次柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        // A：还剩 1 天；B：还剩 2 天（两批都进临期清单、都会生成建议）
        FruitBatch urgent = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 5, "10", "5.00");
        FruitBatch later = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "10", "5.00");
        generate(bizDate);
        Long urgentDecision = decide(suggestionOf(bizDate, urgent.getId()), "7.68");
        decide(suggestionOf(bizDate, later.getId()), "8.96");

        PushPreviewResponse preview = preview(item.getId(), bizDate, "0.80");

        assertThat(preview.batchNo()).isEqualTo(urgent.getBatchNo());
        assertThat(preview.daysLeft()).isEqualTo(1);
        assertThat(preview.discountPrice()).isEqualByComparingTo("7.68");
        assertThat(preview.priceDecisionId()).isEqualTo(urgentDecision);
    }

    @Test
    @DisplayName("现拼发送（只传 itemId）遇到无在售库存：直接说明原因，不建立计划")
    void adhocSendWithoutStockCreatesNoTask() {
        LocalDate bizDate = LocalDate.of(2033, 3, 17);
        FruitItem item = ShopFixtures.item(itemMapper, "无货柚子", ShopFixtures.unique("推送价类"), "5.00", "12.80", 5);
        PushSendRequest request = new PushSendRequest();
        request.setBizDate(bizDate);
        request.setItemId(item.getId());
        request.setSendAt(bizDate.atTime(10, 0));

        PushSendResponse first = pushService.send(request);
        PushSendResponse second = pushService.send(request);

        assertThat(first.sent()).isZero();
        assertThat(first.taskId()).isNull();
        assertThat(first.skipped()).anySatisfy(s -> assertThat(s.reason()).contains("没有在售库存"));
        assertThat(second.taskId()).isNull();
        assertThat(taskMapper.selectCount(Wrappers.<PushTask>lambdaQuery()
                .eq(PushTask::getRelatedItemId, item.getId()))).isZero();
    }

    private void generate(LocalDate bizDate) {
        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy("RULE");
        pricingService.generate(request);
    }

    private PricingSuggestion suggestionOf(LocalDate bizDate, Long batchId) {
        return suggestionMapper.selectOne(Wrappers.<PricingSuggestion>lambdaQuery()
                .eq(PricingSuggestion::getBizDate, bizDate)
                .eq(PricingSuggestion::getBatchId, batchId)
                .orderByDesc(PricingSuggestion::getId)
                .last("LIMIT 1"));
    }

    private Long decide(PricingSuggestion suggestion, String finalPrice) {
        assertThat(suggestion).as("该批次应已生成定价建议").isNotNull();
        DecisionRequest request = new DecisionRequest();
        request.setSuggestionId(suggestion.getId());
        request.setFinalPrice(new BigDecimal(finalPrice));
        request.setReason("测试：店主确认价");
        return pricingService.decide(request).decisionId();
    }

    private PushPreviewResponse preview(Long itemId, LocalDate bizDate, String discountRate) {
        PushPreviewRequest request = new PushPreviewRequest();
        request.setItemId(itemId);
        request.setBizDate(bizDate);
        request.setSendAt(bizDate.atTime(10, 0));
        request.setDiscountRate(new BigDecimal(discountRate));
        return pushService.preview(request);
    }

    private PushSendRequest send(Long taskId, LocalDate bizDate) {
        PushSendRequest request = new PushSendRequest();
        request.setTaskId(taskId);
        request.setSendAt(LocalDateTime.of(bizDate, java.time.LocalTime.of(10, 30)));
        return request;
    }
}
