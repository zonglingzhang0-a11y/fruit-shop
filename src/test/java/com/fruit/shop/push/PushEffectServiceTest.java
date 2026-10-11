package com.fruit.shop.push;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.push.domain.PushLog;
import com.fruit.shop.push.domain.PushTask;
import com.fruit.shop.push.dto.PushAttributionRequest;
import com.fruit.shop.push.dto.PushAttributionResponse;
import com.fruit.shop.push.dto.PushOpenRequest;
import com.fruit.shop.push.dto.PushOpenResponse;
import com.fruit.shop.push.dto.PushPreviewRequest;
import com.fruit.shop.push.dto.PushPreviewResponse;
import com.fruit.shop.push.dto.PushSendRequest;
import com.fruit.shop.push.dto.PushStatsResponse;
import com.fruit.shop.push.dto.PushTaskStat;
import com.fruit.shop.push.mapper.PushLogMapper;
import com.fruit.shop.push.mapper.PushTaskMapper;
import com.fruit.shop.sales.SalesService;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 推送效果归因测试（docs/07 第 3 节验收 4）：
 * 推送后 3 小时内同单品成交记为转化、超窗不计、跨单品不计、退货不计；
 * 打开/转化可标记且幂等；打开率 = 打开数 ÷ 送达数、转化率 = 转化数 ÷ 送达数，
 * 跳过行（限频/静默/退订）与群发文案行都不进分母。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushEffectServiceTest {

    @Autowired
    private PushService pushService;

    @Autowired
    private PushEffectService effectService;

    @Autowired
    private SalesService salesService;

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
    private PushLogMapper logMapper;

    @Autowired
    private SalesRecordMapper salesMapper;

    @Test
    @DisplayName("窗口内同单品成交 → converted=true / converted_at / related_sale_id，且 sales_record.member_id 落库")
    void saleWithinWindowConvertsThePushLog() {
        LocalDate bizDate = LocalDate.of(2038, 4, 1);
        Fixture f = deliveredPush(bizDate, "归因草莓A", "测试归因类A", LocalDateTime.of(2038, 4, 1, 9, 30));

        SalesFeedbackResponse response = salesService.feedback(
                feedback(bizDate, f.item().getId(), f.batch().getId(), "2", "9.00",
                        LocalDateTime.of(2038, 4, 1, 10, 30), f.member().getId(), false));

        assertThat(response.convertedByPush()).isTrue();
        assertThat(response.pushLogId()).isEqualTo(f.log().getId());
        assertThat(response.convertWindowHours()).isEqualTo(3);
        assertThat(response.memberId()).isEqualTo(f.member().getId());
        assertThat(response.attributionNote()).contains("归因成功");

        PushLog converted = logMapper.selectById(f.log().getId());
        assertThat(converted.getConverted()).isTrue();
        assertThat(converted.getConvertedAt()).isEqualTo(LocalDateTime.of(2038, 4, 1, 10, 30));
        assertThat(converted.getRelatedSaleId()).isEqualTo(response.salesRecordId());
        assertThat(converted.getConvertWindowHours()).isEqualTo(3);

        SalesRecord sale = salesMapper.selectById(response.salesRecordId());
        assertThat(sale.getMemberId()).isEqualTo(f.member().getId());
    }

    @Test
    @DisplayName("窗口边界：恰好 3 小时算转化；4 小时不算（超窗不归因）")
    void exactlyThreeHoursConvertsButFourHoursDoesNot() {
        LocalDate bizDate = LocalDate.of(2038, 4, 2);
        Fixture onBoundary = deliveredPush(bizDate, "归因边界梨B", "测试归因类B", LocalDateTime.of(2038, 4, 2, 9, 30));
        SalesFeedbackResponse boundary = salesService.feedback(
                feedback(bizDate, onBoundary.item().getId(), onBoundary.batch().getId(), "1", "9.00",
                        LocalDateTime.of(2038, 4, 2, 12, 30), onBoundary.member().getId(), false));
        assertThat(boundary.convertedByPush()).isTrue();
        assertThat(logMapper.selectById(onBoundary.log().getId()).getConverted()).isTrue();

        LocalDate day2 = LocalDate.of(2038, 4, 3);
        Fixture outOfWindow = deliveredPush(day2, "归因超窗桃C", "测试归因类C", LocalDateTime.of(2038, 4, 3, 9, 30));
        SalesFeedbackResponse late = salesService.feedback(
                feedback(day2, outOfWindow.item().getId(), outOfWindow.batch().getId(), "1", "9.00",
                        LocalDateTime.of(2038, 4, 3, 13, 30), outOfWindow.member().getId(), false));

        assertThat(late.convertedByPush()).isFalse();
        assertThat(late.pushLogId()).isNull();
        assertThat(late.attributionNote()).contains("窗口");
        assertThat(logMapper.selectById(outOfWindow.log().getId()).getConverted()).isFalse();
    }

    @Test
    @DisplayName("跨单品/跨品类不归因：窗口内买了别的单品，不算这条推送的转化")
    void differentItemIsNotAttributed() {
        LocalDate bizDate = LocalDate.of(2038, 4, 4);
        Fixture f = deliveredPush(bizDate, "归因同窗橘D", "测试归因类D", LocalDateTime.of(2038, 4, 4, 9, 30));
        FruitItem other = ShopFixtures.item(itemMapper, "另一个单品D", "测试别的类D", "4.00", "10.00", 5);
        FruitBatch otherBatch = ShopFixtures.batch(batchMapper, other.getId(), bizDate.minusDays(3), 5, "10", "4.00");

        SalesFeedbackResponse response = salesService.feedback(
                feedback(bizDate, other.getId(), otherBatch.getId(), "1", "8.00",
                        LocalDateTime.of(2038, 4, 4, 10, 0), f.member().getId(), false));

        assertThat(response.convertedByPush()).isFalse();
        assertThat(response.attributionNote()).contains("不是同一单品");
        assertThat(logMapper.selectById(f.log().getId()).getConverted()).isFalse();
    }

    @Test
    @DisplayName("退货不归因；窗口内同会员第二笔成交也不会重复挂到同一条 log 上")
    void returnAndSecondSaleDoNotDoubleAttach() {
        LocalDate bizDate = LocalDate.of(2038, 4, 5);
        Fixture f = deliveredPush(bizDate, "归因去重柚E", "测试归因类E", LocalDateTime.of(2038, 4, 5, 9, 30));

        SalesFeedbackResponse first = salesService.feedback(
                feedback(bizDate, f.item().getId(), f.batch().getId(), "1", "9.00",
                        LocalDateTime.of(2038, 4, 5, 10, 0), f.member().getId(), false));
        assertThat(first.convertedByPush()).isTrue();

        // 同会员同单品 3 小时内第二笔：这条 log 已经转化过，不再重复挂
        SalesFeedbackResponse second = salesService.feedback(
                feedback(bizDate, f.item().getId(), f.batch().getId(), "1", "9.00",
                        LocalDateTime.of(2038, 4, 5, 10, 30), f.member().getId(), false));
        assertThat(second.convertedByPush()).isFalse();
        assertThat(second.attributionNote()).contains("没有未转化的送达推送");

        PushLog converted = logMapper.selectById(f.log().getId());
        assertThat(converted.getRelatedSaleId()).isEqualTo(first.salesRecordId());
        assertThat(logMapper.selectCount(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getRelatedSaleId, first.salesRecordId()))).isEqualTo(1L);

        // 退货：不归因、不写 converted
        SalesFeedbackResponse returned = salesService.feedback(
                feedback(bizDate, f.item().getId(), f.batch().getId(), "-1", "9.00",
                        LocalDateTime.of(2038, 4, 5, 11, 0), f.member().getId(), true));
        assertThat(returned.convertedByPush()).isFalse();
        assertThat(returned.attributionNote()).contains("退货");
    }

    @Test
    @DisplayName("手工重跑归因：能把漏掉的销售补上，且重复调用幂等（newlyAttributed 第二次为 0）")
    void manualAttributionIsRepeatableAndIdempotent() {
        LocalDate bizDate = LocalDate.of(2038, 4, 6);
        Fixture f = deliveredPush(bizDate, "归因补跑莓F", "测试归因类F", LocalDateTime.of(2038, 4, 6, 9, 30));

        // 直接落一条 sales_record（模拟"当时没有触发归因"的历史数据）
        SalesRecord sale = new SalesRecord();
        sale.setBizDate(bizDate);
        sale.setSaleTime(LocalDateTime.of(2038, 4, 6, 10, 0));
        sale.setItemId(f.item().getId());
        sale.setBatchId(f.batch().getId());
        sale.setQuantity(new BigDecimal("1"));
        sale.setUnitPrice(new BigDecimal("9.00"));
        sale.setIsDiscount(true);
        sale.setIsReturn(false);
        sale.setMemberId(f.member().getId());
        sale.setSource("REAL");
        salesMapper.insert(sale);

        PushAttributionRequest request = new PushAttributionRequest();
        request.setBizDate(bizDate);

        PushAttributionResponse first = effectService.attribute(request);
        assertThat(first.scanned()).isEqualTo(1);
        assertThat(first.newlyAttributed()).isEqualTo(1);
        assertThat(first.convertedLogIds()).containsExactly(f.log().getId());
        assertThat(first.windowHours()).isEqualTo(3);

        PushAttributionResponse second = effectService.attribute(request);
        assertThat(second.newlyAttributed()).isZero();
        assertThat(second.alreadyAttributed()).isEqualTo(1);

        PushLog converted = logMapper.selectById(f.log().getId());
        assertThat(converted.getConverted()).isTrue();
        assertThat(converted.getRelatedSaleId()).isEqualTo(sale.getId());
        assertThat(logMapper.selectCount(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getRelatedSaleId, sale.getId()))).isEqualTo(1L);
    }

    @Test
    @DisplayName("打开标记幂等（保留首次 opened_at）；打开率/转化率 = 分子 ÷ 送达数，按任务明细可核对")
    void markOpenedIsIdempotentAndStatsExplainNumeratorAndDenominator() {
        LocalDate bizDate = LocalDate.of(2038, 4, 7);
        Fixture f = deliveredPush(bizDate, "打开率测试柠G", "测试归因类G", LocalDateTime.of(2038, 4, 7, 9, 30));

        PushOpenRequest open = new PushOpenRequest();
        open.setLogId(f.log().getId());
        open.setOpenedAt(LocalDateTime.of(2038, 4, 7, 9, 45));
        PushOpenResponse first = effectService.markOpened(open);
        assertThat(first.opened()).isTrue();
        assertThat(first.alreadyOpened()).isFalse();
        assertThat(first.openedAt()).isEqualTo(LocalDateTime.of(2038, 4, 7, 9, 45));

        open.setOpenedAt(LocalDateTime.of(2038, 4, 7, 11, 0));
        PushOpenResponse again = effectService.markOpened(open);
        assertThat(again.alreadyOpened()).isTrue();
        assertThat(again.openedAt()).isEqualTo(LocalDateTime.of(2038, 4, 7, 9, 45));

        salesService.feedback(feedback(bizDate, f.item().getId(), f.batch().getId(), "1", "9.00",
                LocalDateTime.of(2038, 4, 7, 10, 0), f.member().getId(), false));

        PushStatsResponse stats = effectService.stats(bizDate, bizDate, f.task().getId(), true);

        assertThat(stats.delivered()).isEqualTo(1);
        assertThat(stats.opened()).isEqualTo(1);
        assertThat(stats.converted()).isEqualTo(1);
        assertThat(stats.openRate()).isEqualByComparingTo("1.0000");
        assertThat(stats.convertRate()).isEqualByComparingTo("1.0000");
        assertThat(stats.convertWindowHours()).isEqualTo(3);
        assertThat(stats.note()).contains("打开率").contains("送达数").contains("3 小时");
        assertThat(stats.byTask()).singleElement().satisfies(row -> {
            assertThat(row.taskId()).isEqualTo(f.task().getId());
            assertThat(row.status()).isEqualTo(PushTask.STATUS_SENT);
            assertThat(row.delivered()).isEqualTo(1);
            assertThat(row.opened()).isEqualTo(1);
            assertThat(row.converted()).isEqualTo(1);
            assertThat(row.logs()).hasSize(1);
            assertThat(row.logs().get(0).delivered()).isTrue();
            assertThat(row.logs().get(0).opened()).isTrue();
            assertThat(row.logs().get(0).converted()).isTrue();
            assertThat(row.itemName()).isEqualTo(f.item().getItemName());
        });
    }

    @Test
    @DisplayName("限频跳过的行与群发文案行不进分母：送达 1、跳过 1、群发 1 → 打开率分母仍是 1")
    void skippedAndGroupRowsAreNotInTheDenominator() {
        LocalDate bizDate = LocalDate.of(2038, 4, 8);
        FruitItem item = ShopFixtures.item(itemMapper, "分母测试瓜H", "测试归因类H", "5.00", "14.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "5.00");
        Member hot = ShopFixtures.member(memberMapper, "高分会员H", true);
        Member saturated = ShopFixtures.member(memberMapper, "已满额会员H", true);
        ShopFixtures.pref(memberPrefMapper, hot.getId(), item.getCategoryName(), "9.900", 9);
        ShopFixtures.pref(memberPrefMapper, saturated.getId(), item.getCategoryName(), "9.800", 8);

        // 先把 saturated 的每日额度用满（2 条）
        pushService.send(adhocSend(bizDate, item.getId(), saturated.getId(), LocalDateTime.of(2038, 4, 8, 8, 0)));
        pushService.send(adhocSend(bizDate, item.getId(), saturated.getId(), LocalDateTime.of(2038, 4, 8, 8, 5)));

        PushPreviewResponse preview = preview(item.getId(), bizDate, LocalDateTime.of(2038, 4, 8, 9, 0), 2);
        PushSendRequest send = new PushSendRequest();
        send.setTaskId(preview.taskId());
        send.setSendAt(LocalDateTime.of(2038, 4, 8, 9, 30));
        send.setIncludeGroupText(true);
        pushService.send(send);

        PushStatsResponse stats = effectService.stats(bizDate, bizDate, preview.taskId(), true);

        assertThat(stats.delivered()).isEqualTo(1);
        assertThat(stats.skippedLimit()).isEqualTo(1);
        assertThat(stats.groupRows()).isEqualTo(1);
        assertThat(stats.openRate()).isEqualByComparingTo("0.0000");
        assertThat(stats.convertRate()).isEqualByComparingTo("0.0000");

        PushLog skipRow = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, preview.taskId())
                .eq(PushLog::getMemberId, saturated.getId())
                .last("LIMIT 1"));
        assertThat(skipRow.getSkipReason()).isEqualTo(PushLog.SKIP_LIMIT);
        PushLog groupRow = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, preview.taskId())
                .isNull(PushLog::getMemberId)
                .last("LIMIT 1"));
        assertThat(groupRow).isNotNull();

        // 被跳过的行 / 群发行都不能标记打开
        PushOpenRequest onSkip = new PushOpenRequest();
        onSkip.setLogId(skipRow.getId());
        assertThatThrownBy(() -> effectService.markOpened(onSkip))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有送达");
        PushOpenRequest onGroup = new PushOpenRequest();
        onGroup.setLogId(groupRow.getId());
        assertThatThrownBy(() -> effectService.markOpened(onGroup))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群发文案");
    }

    @Test
    @DisplayName("只给 taskId 查统计时不限日期：历史计划不会因为默认最近 7 天而被过滤掉")
    void statsByTaskIdWithoutDatesFindsHistoricalPlan() {
        LocalDate bizDate = LocalDate.of(2038, 4, 9);
        Fixture f = deliveredPush(bizDate, "历史计划橙I", "测试归因类I", LocalDateTime.of(2038, 4, 9, 9, 30));

        PushStatsResponse stats = effectService.stats(null, null, f.task().getId(), false);

        assertThat(stats.delivered()).isEqualTo(1);
        assertThat(stats.taskCount()).isEqualTo(1);
        assertThat(stats.byTask()).singleElement()
                .satisfies(row -> assertThat(row.bizDate()).isEqualTo(bizDate));
        assertThat(stats.note()).contains("不限日期");
        assertThat(stats.from()).isNull();
    }

    @Test
    @DisplayName("没有数据的区间返回 0 与 0.0000 而不是报错")
    void statsWithoutDataReturnZeros() {
        PushStatsResponse stats = effectService.stats(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 1, 7), null, false);

        assertThat(stats.taskCount()).isZero();
        assertThat(stats.delivered()).isZero();
        assertThat(stats.openRate()).isEqualByComparingTo("0.0000");
        assertThat(stats.convertRate()).isEqualByComparingTo("0.0000");
        assertThat(stats.byTask()).isEmpty();
    }

    // ------------------------------------------------------------------ 工具

    private record Fixture(FruitItem item, FruitBatch batch, Member member, PushTask task, PushLog log) {
    }

    /** 造一个"已经发出去"的推送：商品 + 在售批次 + 命中品类偏好的会员 + 计划 + 一条送达日志。 */
    private Fixture deliveredPush(LocalDate bizDate, String name, String category, LocalDateTime sendAt) {
        FruitItem item = ShopFixtures.item(itemMapper, name, category, "5.00", "12.00", 5);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "30", "5.00");
        Member member = ShopFixtures.member(memberMapper, name + "会员", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), category, "9.000", 5);

        PushPreviewResponse preview = preview(item.getId(), bizDate, sendAt.minusMinutes(30), null);
        PushSendRequest send = new PushSendRequest();
        send.setTaskId(preview.taskId());
        send.setSendAt(sendAt);
        send.setIncludeGroupText(false);
        pushService.send(send);

        PushTask task = taskMapper.selectById(preview.taskId());
        PushLog log = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, task.getId())
                .eq(PushLog::getMemberId, member.getId())
                .last("LIMIT 1"));
        assertThat(log).as("应有一条送达日志").isNotNull();
        assertThat(log.getSkipReason()).isNull();
        return new Fixture(item, batch, member, task, log);
    }

    private PushPreviewResponse preview(Long itemId, LocalDate bizDate, LocalDateTime sendAt, Integer targetCount) {
        PushPreviewRequest request = new PushPreviewRequest();
        request.setItemId(itemId);
        request.setBizDate(bizDate);
        request.setSendAt(sendAt);
        request.setTargetCount(targetCount);
        request.setDiscountRate(new BigDecimal("0.60"));
        return pushService.preview(request);
    }

    private PushSendRequest adhocSend(LocalDate bizDate, Long itemId, Long memberId, LocalDateTime sendAt) {
        PushSendRequest request = new PushSendRequest();
        request.setBizDate(bizDate);
        request.setItemId(itemId);
        request.setSendAt(sendAt);
        request.setMemberIds(List.of(memberId));
        request.setIncludeGroupText(false);
        return request;
    }

    private SalesFeedbackRequest feedback(LocalDate bizDate, Long itemId, Long batchId, String quantity,
                                          String unitPrice, LocalDateTime saleTime, Long memberId, boolean isReturn) {
        SalesFeedbackRequest request = new SalesFeedbackRequest();
        request.setBizDate(bizDate);
        request.setItemId(itemId);
        request.setBatchId(batchId);
        request.setQuantity(new BigDecimal(quantity));
        request.setUnitPrice(new BigDecimal(unitPrice));
        request.setSaleTime(saleTime);
        request.setMemberId(memberId);
        request.setIsReturn(isReturn);
        return request;
    }
}
