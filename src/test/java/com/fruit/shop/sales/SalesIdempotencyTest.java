package com.fruit.shop.sales;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.pricing.PricingService;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.push.PushService;
import com.fruit.shop.push.dto.PushPreviewRequest;
import com.fruit.shop.push.dto.PushSendRequest;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
import com.fruit.shop.support.ApiResult;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 销售回流幂等（Issue #3）：同一业务流水号 requestNo 只记一次。
 *
 * <p>业务日用 2033 年：与其它共用内存库的测试错开（见 PushPriceTest 的说明）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesIdempotencyTest {

    private static final int THREADS = 10;

    @Autowired
    private SalesService salesService;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private SalesRecordMapper salesMapper;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private MemberPrefMapper memberPrefMapper;

    @Autowired
    private PushService pushService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private StrategyDailyStatMapper statMapper;

    @Test
    @DisplayName("顺序重复提交：第二次返回 duplicate=true 与首笔流水 id，库存只扣一次、流水只有一条")
    void sequentialDuplicateIsRecordedOnce() {
        LocalDate bizDate = LocalDate.of(2033, 7, 1);
        FruitBatch batch = newBatch(bizDate, "幂等顺序梨", "20");

        SalesFeedbackResponse first = salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-SEQ-1"));
        SalesFeedbackResponse again = salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-SEQ-1"));

        assertThat(first.duplicate()).isFalse();
        assertThat(first.requestNo()).isEqualTo("POS-SEQ-1");
        assertThat(again.duplicate()).isTrue();
        assertThat(again.salesRecordId()).isEqualTo(first.salesRecordId());
        assertThat(again.statUpdated()).isFalse();
        assertThat(again.remainQty()).isEqualByComparingTo("17");
        assertThat(again.message()).contains("重复提交").contains("没有重复扣减库存");
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("17");
        assertThat(recordsOf(batch.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("并发重复提交：10 个线程带同一流水号，只有 1 笔真正记账，其余都是 duplicate")
    void concurrentDuplicateIsRecordedOnce() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 7, 2);
        FruitBatch batch = newBatch(bizDate, "幂等并发梨", "20");

        List<SalesFeedbackResponse> responses = runConcurrently(() -> salesService.feedback(
                feedback(bizDate, batch.getId(), "2", "8.00", "POS-CON-1")));

        assertThat(responses).hasSize(THREADS);
        assertThat(responses.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
        assertThat(responses.stream().map(SalesFeedbackResponse::salesRecordId).distinct().count()).isEqualTo(1);
        // 重复的那几笔返回的是批次当前剩余（18），不是加锁前缓存的旧值（20）
        assertThat(responses).allSatisfy(r -> assertThat(r.remainQty()).isEqualByComparingTo("18"));
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("18");
        assertThat(recordsOf(batch.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("三位小数数量（如称重 1.235 斤）原样重试：按两位小数比对，判为重复而不是 409")
    void retryWithThreeDecimalQuantityIsDuplicateNotConflict() {
        LocalDate bizDate = LocalDate.of(2033, 7, 8);
        FruitBatch batch = newBatch(bizDate, "称重幂等梨", "20");

        SalesFeedbackResponse first = salesService.feedback(feedback(bizDate, batch.getId(), "1.235", "8.00", "POS-SCALE-1"));
        SalesFeedbackResponse again = salesService.feedback(feedback(bizDate, batch.getId(), "1.235", "8.00", "POS-SCALE-1"));

        assertThat(first.soldQty()).isEqualByComparingTo("1.24");
        assertThat(again.duplicate()).isTrue();
        assertThat(again.salesRecordId()).isEqualTo(first.salesRecordId());
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("18.76");
    }

    @Test
    @DisplayName("首笔库存不足被截断：重试原样回放 overSell=true、请求 3 实扣 1，不再扣减")
    void duplicateReplaysOverSell() {
        LocalDate bizDate = LocalDate.of(2033, 7, 9);
        FruitBatch batch = newBatch(bizDate, "截断幂等梨", "1");

        SalesFeedbackResponse first = salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-OVER-1"));
        SalesFeedbackResponse again = salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-OVER-1"));

        assertThat(first.overSell()).isTrue();
        assertThat(again.duplicate()).isTrue();
        assertThat(again.overSell()).isTrue();
        assertThat(again.requestedQty()).isEqualByComparingTo("3");
        assertThat(again.soldQty()).isEqualByComparingTo("1");
        assertThat(again.remainQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("同一流水号换了数量（更大）或换了业务日：都拒绝（409），第二笔不会被静默吞掉")
    void reusedRequestNoWithLargerQuantityOrOtherDateIsRejected() {
        LocalDate bizDate = LocalDate.of(2033, 7, 10);
        FruitBatch batch = newBatch(bizDate, "换量幂等梨", "20");
        salesService.feedback(feedback(bizDate, batch.getId(), "5", "8.00", "POS-REUSE-1"));
        salesService.feedback(feedback(bizDate, batch.getId(), "-2", "8.00", "POS-REUSE-RET"));

        assertThatThrownBy(() -> salesService.feedback(feedback(bizDate, batch.getId(), "9", "8.00", "POS-REUSE-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ApiResult.CODE_CONFLICT));
        assertThatThrownBy(() -> salesService.feedback(feedback(bizDate, batch.getId(), "-4", "8.00", "POS-REUSE-RET")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> salesService.feedback(feedback(bizDate.plusDays(1), batch.getId(), "5", "8.00", "POS-REUSE-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("业务日");
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("17");
    }

    @Test
    @DisplayName("首笔命中推送归因：重试原样回放 pushLogId 与 convertedByPush=true")
    void duplicateReplaysPushAttribution() {
        LocalDate bizDate = LocalDate.of(2033, 7, 11);
        FruitBatch batch = newBatch(bizDate, "归因幂等梨", "20");
        FruitItem item = itemMapper.selectById(batch.getItemId());
        Member member = ShopFixtures.member(memberMapper, "归因幂等会员", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "9.000", 5);
        LocalDateTime sendAt = bizDate.atTime(10, 0);
        PushPreviewRequest preview = new PushPreviewRequest();
        preview.setItemId(item.getId());
        preview.setBizDate(bizDate);
        preview.setSendAt(sendAt);
        PushSendRequest send = new PushSendRequest();
        send.setTaskId(pushService.preview(preview).taskId());
        send.setSendAt(sendAt);
        send.setIncludeGroupText(false);
        pushService.send(send);

        SalesFeedbackRequest sale = feedback(bizDate, batch.getId(), "1", "8.00", "POS-ATTR-1");
        sale.setMemberId(member.getId());
        sale.setSaleTime(sendAt.plusHours(1));
        SalesFeedbackResponse first = salesService.feedback(sale);
        SalesFeedbackResponse again = salesService.feedback(sale);

        assertThat(first.convertedByPush()).isTrue();
        assertThat(again.duplicate()).isTrue();
        assertThat(again.convertedByPush()).isTrue();
        assertThat(again.pushLogId()).isEqualTo(first.pushLogId());
    }

    @Test
    @DisplayName("FIFO 自动选批 + 并发重复提交：同样只记一笔")
    void concurrentDuplicateWithFifoPickIsRecordedOnce() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 7, 3);
        FruitBatch batch = newBatch(bizDate, "幂等FIFO梨", "20");

        List<SalesFeedbackResponse> responses = runConcurrently(() -> salesService.feedback(
                feedback(bizDate, null, batch.getItemId(), "2", "8.00", "POS-FIFO-1")));

        assertThat(responses.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("18");
        assertThat(recordsOf(batch.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("没有在售批次（不加行锁）+ 并发重复提交：靠唯一索引兜底，撞索引的那笔回滚后返回首笔")
    void concurrentDuplicateWithoutBatchFallsBackToUniqueIndex() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 7, 7);
        FruitItem item = ShopFixtures.item(itemMapper, "无批次幂等梨", ShopFixtures.unique("幂等类"), "4.00", "10.00", 8);

        List<SalesFeedbackResponse> responses = runConcurrently(() -> salesService.feedback(
                feedback(bizDate, null, item.getId(), "1", "8.00", "POS-NOBATCH-1")));

        assertThat(responses).hasSize(THREADS);
        assertThat(responses.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
        assertThat(responses.stream().map(SalesFeedbackResponse::salesRecordId).distinct().count()).isEqualTo(1);
        assertThat(salesMapper.selectCount(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getRequestNo, "POS-NOBATCH-1"))).isEqualTo(1);
    }

    @Test
    @DisplayName("同一流水号的并发请求锁到不同批次（一半指定后到期批次、一半 FIFO 选先到期批次）：撞唯一索引的输家连同库存扣减整体回滚，总共只扣一次")
    void losingDuplicateRollsBackItsStockDeduction() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 7, 12);
        FruitItem item = ShopFixtures.item(itemMapper, "回滚幂等梨", ShopFixtures.unique("幂等类"), "4.00", "10.00", 8);
        FruitBatch early = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(4), 8, "20", "4.00");
        FruitBatch late = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 8, "20", "4.00");

        // 两组请求内容不同（一组指定了批次），锁的是不同的行，可能同时通过锁后查重、一起走到插入：
        // 输家撞唯一索引 → 整笔回滚（含已做的库存扣减）→ 新事务里查到首笔；内容与首笔不一致的那组得到 409。
        // 不论每个请求得到"成功 / 重复 / 409"哪种结果，库存都只能扣一次
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        List<SalesFeedbackResponse> responses = runConcurrently(() -> {
            boolean explicitLate = seq.getAndIncrement() % 2 == 0;
            try {
                return salesService.feedback(explicitLate
                        ? feedback(bizDate, late.getId(), item.getId(), "2", "8.00", "POS-ROLLBACK-1")
                        : feedback(bizDate, null, item.getId(), "2", "8.00", "POS-ROLLBACK-1"));
            } catch (BusinessException e) {
                assertThat(e.getCode()).isEqualTo(ApiResult.CODE_CONFLICT);
                conflicts.incrementAndGet();
                return null;
            }
        });

        long recorded = responses.stream().filter(r -> !r.duplicate()).count();
        BigDecimal remainTotal = batchMapper.selectById(early.getId()).getRemainQty()
                .add(batchMapper.selectById(late.getId()).getRemainQty());
        assertThat(recorded).isEqualTo(1);
        assertThat(responses.size() + conflicts.get()).isEqualTo(THREADS);
        assertThat(remainTotal).as("两个批次合计只扣了一笔的 2 件").isEqualByComparingTo("38");
        assertThat(salesMapper.selectCount(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getRequestNo, "POS-ROLLBACK-1"))).isEqualTo(1);
    }

    @Test
    @DisplayName("重复提交不写策略日统计：首笔确实累加了统计，顺序 + 并发重复之后仍只算一笔")
    void duplicatesDoNotTouchStrategyStats() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 7, 13);
        FruitItem item = ShopFixtures.item(itemMapper, "统计幂等梨", ShopFixtures.unique("幂等类"), "4.00", "10.00", 5);
        // 剩余 2 天进临期清单 → 生成 RULE 建议，回流才能推断出策略、真正写统计
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "4.00");
        SuggestionGenerateRequest generate = new SuggestionGenerateRequest();
        generate.setBizDate(bizDate);
        generate.setStrategy("RULE");
        pricingService.generate(generate);

        SalesFeedbackResponse first = salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-STAT-1"));
        salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-STAT-1"));
        runConcurrently(() -> salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-STAT-1")));

        assertThat(first.strategy()).isEqualTo("RULE");
        assertThat(first.statUpdated()).as("首笔必须真的写了统计，下面的断言才有意义").isTrue();
        StrategyDailyStat stat = statMapper.selectOne(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate).eq(StrategyDailyStat::getStrategy, "RULE"));
        assertThat(stat.getSoldQty()).isEqualByComparingTo("3");
        assertThat(stat.getRevenue()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("退货也幂等：同一退货流水号重复提交只回补一次")
    void duplicateReturnRestocksOnce() {
        LocalDate bizDate = LocalDate.of(2033, 7, 4);
        FruitBatch batch = newBatch(bizDate, "幂等退货梨", "20");
        salesService.feedback(feedback(bizDate, batch.getId(), "5", "8.00", "POS-RET-SALE"));

        SalesFeedbackResponse back = salesService.feedback(feedback(bizDate, batch.getId(), "-2", "8.00", "POS-RET-1"));
        SalesFeedbackResponse again = salesService.feedback(feedback(bizDate, batch.getId(), "-2", "8.00", "POS-RET-1"));

        assertThat(back.duplicate()).isFalse();
        assertThat(again.duplicate()).isTrue();
        assertThat(again.isReturn()).isTrue();
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("17");
    }

    @Test
    @DisplayName("同一流水号换了单价/方向：拒绝（409），不悄悄返回旧结果，也不扣库存")
    void reusedRequestNoWithDifferentContentIsRejected() {
        LocalDate bizDate = LocalDate.of(2033, 7, 5);
        FruitBatch batch = newBatch(bizDate, "幂等冲突梨", "20");
        salesService.feedback(feedback(bizDate, batch.getId(), "3", "8.00", "POS-CONFLICT-1"));

        assertThatThrownBy(() -> salesService.feedback(feedback(bizDate, batch.getId(), "3", "6.50", "POS-CONFLICT-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("POS-CONFLICT-1")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ApiResult.CODE_CONFLICT));
        assertThatThrownBy(() -> salesService.feedback(feedback(bizDate, batch.getId(), "-3", "8.00", "POS-CONFLICT-1")))
                .isInstanceOf(BusinessException.class);
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("17");
        assertThat(recordsOf(batch.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("流水号区分大小写：'POS-Case-a' 与 'pos-case-A' 是两笔不同的销售（MySQL 上靠 utf8mb4_bin 保证）")
    void requestNoIsCaseSensitive() {
        LocalDate bizDate = LocalDate.of(2033, 7, 14);
        FruitBatch batch = newBatch(bizDate, "大小写幂等梨", "20");

        SalesFeedbackResponse first = salesService.feedback(feedback(bizDate, batch.getId(), "1", "8.00", "POS-Case-a"));
        SalesFeedbackResponse second = salesService.feedback(feedback(bizDate, batch.getId(), "1", "8.00", "pos-case-A"));

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).as("只是大小写不同的流水号不能被当成重复").isFalse();
        assertThat(second.salesRecordId()).isNotEqualTo(first.salesRecordId());
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("18");
    }

    @Test
    @DisplayName("不传流水号：保持原行为，每次调用都记一笔")
    void withoutRequestNoEveryCallIsRecorded() {
        LocalDate bizDate = LocalDate.of(2033, 7, 6);
        FruitBatch batch = newBatch(bizDate, "无流水号梨", "20");

        SalesFeedbackResponse first = salesService.feedback(feedback(bizDate, batch.getId(), "1", "8.00", null));
        SalesFeedbackResponse second = salesService.feedback(feedback(bizDate, batch.getId(), "1", "8.00", "  "));

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isFalse();
        assertThat(second.requestNo()).isNull();
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("18");
        assertThat(recordsOf(batch.getId())).isEqualTo(2);
    }

    private FruitBatch newBatch(LocalDate bizDate, String name, String qty) {
        FruitItem item = ShopFixtures.item(itemMapper, name, ShopFixtures.unique("幂等类"), "4.00", "10.00", 8);
        return ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 8, qty, "4.00");
    }

    private long recordsOf(Long batchId) {
        return salesMapper.selectCount(Wrappers.<SalesRecord>lambdaQuery().eq(SalesRecord::getBatchId, batchId));
    }

    private List<SalesFeedbackResponse> runConcurrently(java.util.concurrent.Callable<SalesFeedbackResponse> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SalesFeedbackResponse>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<SalesFeedbackResponse> responses = new ArrayList<>();
            for (Future<SalesFeedbackResponse> future : futures) {
                SalesFeedbackResponse response = future.get(30, TimeUnit.SECONDS);
                if (response != null) {
                    responses.add(response);
                }
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private SalesFeedbackRequest feedback(LocalDate bizDate, Long batchId, String quantity, String unitPrice,
                                          String requestNo) {
        return feedback(bizDate, batchId, null, quantity, unitPrice, requestNo);
    }

    private SalesFeedbackRequest feedback(LocalDate bizDate, Long batchId, Long itemId, String quantity,
                                          String unitPrice, String requestNo) {
        SalesFeedbackRequest request = new SalesFeedbackRequest();
        request.setBizDate(bizDate);
        request.setBatchId(batchId);
        request.setItemId(itemId);
        request.setQuantity(new BigDecimal(quantity));
        request.setUnitPrice(new BigDecimal(unitPrice));
        request.setRequestNo(requestNo);
        return request;
    }
}
