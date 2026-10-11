package com.fruit.shop.sales;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.pricing.PricingService;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import com.fruit.shop.sales.dto.EffectResponse;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
import com.fruit.shop.sales.dto.StrategySummary;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 销售回流与效果看板测试。
 * 重点覆盖：remain_qty 扣到 0 → 批次转 SOLD_OUT；超量回流被截断并如实上报；退货回补；
 * 策略日统计按 strategy 聚合，效果看板能按日给趋势。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesServiceTest {

    @Autowired
    private SalesService salesService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private SalesRecordMapper salesMapper;

    @Autowired
    private StrategyDailyStatMapper statMapper;

    @Autowired
    private MemberMapper memberMapper;

    @Test
    @DisplayName("回流扣减 remain_qty：扣到 0 时批次转 SOLD_OUT，并写入销售流水与策略日统计")
    void feedbackDeductsRemainQtyAndMarksSoldOutAtZero() {
        LocalDate bizDate = LocalDate.of(2035, 4, 1);
        FruitItem item = ShopFixtures.item(itemMapper, "回流测试桃", "测试回流类", "5.00", "12.00", 6);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 6, "10", "5.00");
        generateSuggestion(bizDate, "RULE");

        SalesFeedbackResponse first = salesService.feedback(feedback(bizDate, item.getId(), batch.getId(), "4", "8.00"));

        assertThat(first.remainQty()).isEqualByComparingTo("6");
        assertThat(first.batchStatus()).isEqualTo("ON_SALE");
        assertThat(first.overSell()).isFalse();
        assertThat(first.salesRecordId()).isNotNull();
        assertThat(first.strategy()).isEqualTo("RULE");
        assertThat(first.statUpdated()).isTrue();
        assertThat(first.message()).contains("已回流 4");

        SalesFeedbackResponse second = salesService.feedback(feedback(bizDate, item.getId(), batch.getId(), "6", "8.00"));

        assertThat(second.remainQty()).isEqualByComparingTo("0");
        assertThat(second.batchStatus()).isEqualTo("SOLD_OUT");
        assertThat(second.soldQty()).isEqualByComparingTo("6");

        FruitBatch stored = batchMapper.selectById(batch.getId());
        assertThat(stored.getRemainQty()).isEqualByComparingTo("0");
        assertThat(stored.getStatus()).isEqualTo("SOLD_OUT");
        assertThat(stored.getInitQty()).isEqualByComparingTo("10");

        assertThat(salesMapper.selectCount(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getBizDate, bizDate)
                .eq(SalesRecord::getBatchId, batch.getId()))).isEqualTo(2L);

        StrategyDailyStat stat = statMapper.selectOne(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate)
                .eq(StrategyDailyStat::getStrategy, "RULE")
                .last("LIMIT 1"));
        assertThat(stat).isNotNull();
        assertThat(stat.getSoldQty()).isEqualByComparingTo("10.00");
        assertThat(stat.getRevenue()).isEqualByComparingTo("80.00");
        assertThat(stat.getCost()).isEqualByComparingTo("50.00");
        assertThat(stat.getGrossProfit()).isEqualByComparingTo("30.00");
        assertThat(stat.getScrapRate()).isEqualByComparingTo("0.0000");

        EffectResponse effect = salesService.effect(bizDate, bizDate, "RULE");
        assertThat(effect.source()).isEqualTo("strategy_daily_stat");
        assertThat(effect.activeStrategy()).isEqualTo("RULE");
        assertThat(effect.soldQty()).isEqualByComparingTo("10.00");
        assertThat(effect.revenue()).isEqualByComparingTo("80.00");
        assertThat(effect.cost()).isEqualByComparingTo("50.00");
        assertThat(effect.grossProfit()).isEqualByComparingTo("30.00");
        assertThat(effect.scrapRate()).isEqualByComparingTo("0.0000");
        assertThat(effect.daily()).isNotEmpty();
        assertThat(effect.daily().get(0).bizDate()).isEqualTo(bizDate);
        assertThat(effect.daily().get(0).revenue()).isEqualByComparingTo("80.00");
        assertThat(effect.byStrategy()).extracting(StrategySummary::strategy).contains("RULE");
    }

    @Test
    @DisplayName("超量回流：按现存量截断、remain_qty 不为负，overSell=true 如实上报")
    void overSellIsClampedAndReported() {
        LocalDate bizDate = LocalDate.of(2035, 4, 2);
        FruitItem item = ShopFixtures.item(itemMapper, "超量测试梨", "测试超量类", "3.00", "8.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "5", "3.00");

        SalesFeedbackResponse response = salesService.feedback(feedback(bizDate, item.getId(), batch.getId(), "99", "7.00"));

        assertThat(response.overSell()).isTrue();
        assertThat(response.soldQty()).isEqualByComparingTo("5");
        assertThat(response.remainQty()).isEqualByComparingTo("0");
        assertThat(response.batchStatus()).isEqualTo("SOLD_OUT");
        assertThat(response.message()).contains("超过现存量");
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("0");

        // 已售罄的批次再回流：不再产生扣减，也不再写一条数量为 0 的流水
        SalesFeedbackResponse again = salesService.feedback(feedback(bizDate, item.getId(), batch.getId(), "3", "7.00"));
        assertThat(again.soldQty()).isEqualByComparingTo("0");
        assertThat(again.overSell()).isTrue();
        assertThat(again.salesRecordId()).isNull();
        assertThat(again.remainQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("退货：remain_qty 回补，已售罄批次回到 ON_SALE，统计同步回退")
    void returnRestoresRemainQtyAndReopensBatch() {
        LocalDate bizDate = LocalDate.of(2035, 4, 3);
        FruitItem item = ShopFixtures.item(itemMapper, "退货测试果", "测试退货类", "4.00", "10.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "5", "4.00");
        generateSuggestion(bizDate, "RULE");

        salesService.feedback(feedback(bizDate, item.getId(), batch.getId(), "5", "8.00"));
        assertThat(batchMapper.selectById(batch.getId()).getStatus()).isEqualTo("SOLD_OUT");

        SalesFeedbackRequest request = feedback(bizDate, item.getId(), batch.getId(), "-2", "8.00");
        SalesFeedbackResponse response = salesService.feedback(request);

        assertThat(response.isReturn()).isTrue();
        assertThat(response.remainQty()).isEqualByComparingTo("2");
        assertThat(response.batchStatus()).isEqualTo("ON_SALE");

        FruitBatch stored = batchMapper.selectById(batch.getId());
        assertThat(stored.getRemainQty()).isEqualByComparingTo("2");
        assertThat(stored.getStatus()).isEqualTo("ON_SALE");

        SalesRecord salesReturn = salesMapper.selectOne(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getBatchId, batch.getId())
                .eq(SalesRecord::getIsReturn, true)
                .last("LIMIT 1"));
        assertThat(salesReturn).isNotNull();
        assertThat(salesReturn.getQuantity()).isEqualByComparingTo("-2");

        StrategyDailyStat stat = statMapper.selectOne(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate)
                .eq(StrategyDailyStat::getStrategy, "RULE")
                .last("LIMIT 1"));
        assertThat(stat.getSoldQty()).isEqualByComparingTo("3.00");
        assertThat(stat.getRevenue()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("不传 batchId 时按 FIFO 自动挑最早到期的在售批次")
    void feedbackWithoutBatchIdPicksFifoBatch() {
        LocalDate bizDate = LocalDate.of(2035, 4, 4);
        FruitItem item = ShopFixtures.item(itemMapper, "先进先出果", "测试FIFO类", "4.00", "9.00", 8);
        FruitBatch later = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 8, "6", "4.00");
        FruitBatch earlier = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "6", "4.00");

        SalesFeedbackRequest request = feedback(bizDate, item.getId(), null, "2", "7.00");
        SalesFeedbackResponse response = salesService.feedback(request);

        assertThat(response.batchAutoPicked()).isTrue();
        assertThat(response.batchId()).isEqualTo(earlier.getId());
        assertThat(response.remainQty()).isEqualByComparingTo("4");
        assertThat(batchMapper.selectById(later.getId()).getRemainQty()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("效果看板：没有策略日统计时回退 sales_record 实时汇总，并在 note 里如实说明")
    void effectFallsBackToSalesRecordsWhenNoStats() {
        LocalDate bizDate = LocalDate.of(2035, 4, 20);
        FruitItem item = ShopFixtures.item(itemMapper, "回退测试果", "测试回退类", "6.00", "14.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "10", "6.00");
        // 刻意不生成定价建议 → 无法归属策略 → 不会写 strategy_daily_stat
        salesService.feedback(feedback(bizDate, item.getId(), batch.getId(), "3", "11.00"));

        assertThat(statMapper.selectCount(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate))).isZero();

        EffectResponse effect = salesService.effect(bizDate, bizDate, null);

        assertThat(effect.source()).isEqualTo("sales_record");
        assertThat(effect.soldQty()).isEqualByComparingTo("3.00");
        assertThat(effect.revenue()).isEqualByComparingTo("33.00");
        assertThat(effect.cost()).isEqualByComparingTo("18.00");
        assertThat(effect.grossProfit()).isEqualByComparingTo("15.00");
        assertThat(effect.daily()).hasSize(1);
        assertThat(effect.daily().get(0).strategy()).isEqualTo("REAL");
        assertThat(effect.note()).contains("sales_record");
        assertThat(effect.byStrategy()).isEmpty();
    }

    @Test
    @DisplayName("效果看板：区间内完全没有数据时返回 0 而不是报错（无数据也能启动）")
    void effectWithNoDataAtAllReturnsZeros() {
        EffectResponse effect = salesService.effect(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 1, 7), null);

        assertThat(effect.soldQty()).isEqualByComparingTo("0.00");
        assertThat(effect.revenue()).isEqualByComparingTo("0.00");
        assertThat(effect.grossProfit()).isEqualByComparingTo("0.00");
        assertThat(effect.daily()).isEmpty();
        assertThat(effect.note()).isNotBlank();
    }

    @Test
    @DisplayName("回流参数不合法 → 400 人话报错")
    void invalidFeedbackIsRejected() {
        LocalDate bizDate = LocalDate.of(2035, 4, 5);
        SalesFeedbackRequest noTarget = feedback(bizDate, null, null, "1", "5.00");
        assertThatThrownBy(() -> salesService.feedback(noTarget))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("itemId 与 batchId 至少传一个");

        FruitItem item = ShopFixtures.item(itemMapper, "零数量果", "测试零量类", "3.00", "7.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 5, "5", "3.00");
        SalesFeedbackRequest zero = feedback(bizDate, item.getId(), batch.getId(), "0", "5.00");
        assertThatThrownBy(() -> salesService.feedback(zero))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quantity 不能为 0");

        SalesFeedbackRequest mismatched = feedback(bizDate, -1L, batch.getId(), "1", "5.00");
        assertThatThrownBy(() -> salesService.feedback(mismatched))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("itemId 与批次不匹配");
    }

    @Test
    @DisplayName("可选 memberId：写入 sales_record.member_id 并触发归因链路（无匹配推送时如实说明）")
    void feedbackStoresOptionalMemberIdAndRunsAttribution() {
        LocalDate bizDate = LocalDate.of(2038, 4, 25);
        FruitItem item = ShopFixtures.item(itemMapper, "会员回流果", "测试会员回流类", "5.00", "12.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "8", "5.00");
        Member member = ShopFixtures.member(memberMapper, "回流会员", true);

        SalesFeedbackRequest request = feedback(bizDate, item.getId(), batch.getId(), "2", "9.00");
        request.setMemberId(member.getId());
        request.setSaleTime(java.time.LocalDateTime.of(2038, 4, 25, 10, 0));
        SalesFeedbackResponse response = salesService.feedback(request);

        assertThat(response.memberId()).isEqualTo(member.getId());
        assertThat(response.convertedByPush()).isFalse();
        assertThat(response.pushLogId()).isNull();
        assertThat(response.convertWindowHours()).isEqualTo(3);
        assertThat(response.attributionNote()).contains("窗口");

        SalesRecord stored = salesMapper.selectById(response.salesRecordId());
        assertThat(stored.getMemberId()).isEqualTo(member.getId());
    }

    @Test
    @DisplayName("不传 memberId：如实说明本笔不参与推送转化归因（默认行为不变）")
    void feedbackWithoutMemberIdIsAttributionFree() {
        LocalDate bizDate = LocalDate.of(2038, 4, 26);
        FruitItem item = ShopFixtures.item(itemMapper, "无会员回流果", "测试无会员类", "4.00", "10.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");

        SalesFeedbackResponse response = salesService.feedback(
                feedback(bizDate, item.getId(), batch.getId(), "1", "8.00"));

        assertThat(response.memberId()).isNull();
        assertThat(response.convertedByPush()).isFalse();
        assertThat(response.attributionNote()).contains("没有 member_id");
        assertThat(salesMapper.selectById(response.salesRecordId()).getMemberId()).isNull();
    }

    @Test
    @DisplayName("memberId 不存在 → 400 人话报错，不写脏数据")
    void feedbackWithUnknownMemberIsRejected() {
        LocalDate bizDate = LocalDate.of(2038, 4, 27);
        FruitItem item = ShopFixtures.item(itemMapper, "脏会员回流果", "测试脏会员类", "4.00", "10.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "6", "4.00");

        SalesFeedbackRequest request = feedback(bizDate, item.getId(), batch.getId(), "1", "8.00");
        request.setMemberId(987654321L);

        assertThatThrownBy(() -> salesService.feedback(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会员不存在");
        assertThat(salesMapper.selectCount(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getBizDate, bizDate))).isZero();
    }

    private void generateSuggestion(LocalDate bizDate, String strategy) {
        SuggestionGenerateRequest request = new SuggestionGenerateRequest();
        request.setBizDate(bizDate);
        request.setStrategy(strategy);
        pricingService.generate(request);
    }

    private SalesFeedbackRequest feedback(LocalDate bizDate, Long itemId, Long batchId, String quantity, String unitPrice) {
        SalesFeedbackRequest request = new SalesFeedbackRequest();
        request.setBizDate(bizDate);
        request.setItemId(itemId);
        request.setBatchId(batchId);
        request.setQuantity(new BigDecimal(quantity));
        request.setUnitPrice(new BigDecimal(unitPrice));
        return request;
    }
}
