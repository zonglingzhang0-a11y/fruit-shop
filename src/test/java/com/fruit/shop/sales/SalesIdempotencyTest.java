package com.fruit.shop.sales;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.support.ApiResult;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("18");
        assertThat(recordsOf(batch.getId())).isEqualTo(1);
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
                responses.add(future.get(30, TimeUnit.SECONDS));
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
