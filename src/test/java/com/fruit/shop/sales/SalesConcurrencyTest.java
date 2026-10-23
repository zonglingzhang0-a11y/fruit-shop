package com.fruit.shop.sales;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
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

/**
 * 并发销售回流回归测试（Issue #1 队友复现：库存 20，10 笔并发各卖 1，剩余应为 10，修复前实际为 19）。
 * 锁定三件事：批次 remain_qty 不丢扣减；并发下不超卖；strategy_daily_stat 累加不丢。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesConcurrencyTest {

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
    private StrategyDailyStatMapper statMapper;

    @Autowired
    private StrategyStatService statService;

    @Test
    @DisplayName("并发回流：10 笔各卖 1，库存 20 → 10，销售流水 10 条")
    void concurrentFeedbackDoesNotLoseDeductions() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 5, 6);
        FruitItem item = ShopFixtures.item(itemMapper, "并发测试柚", "并发测试类", "5.00", "12.80", 8);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 8, "20", "5.00");

        List<SalesFeedbackResponse> responses = runConcurrently(() -> salesService.feedback(
                feedback(bizDate, item.getId(), batch.getId(), "1")));

        assertThat(responses).hasSize(THREADS).allSatisfy(r -> assertThat(r.soldQty()).isEqualByComparingTo("1"));
        assertThat(batchMapper.selectById(batch.getId()).getRemainQty()).isEqualByComparingTo("10");
        assertThat(salesMapper.selectCount(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getBatchId, batch.getId()))).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("并发累加策略日统计：10 次各 +1，同一 (日期,策略) 只有一行且销量为 10、毛利同步累加")
    void concurrentStatAccumulateDoesNotLoseUpdates() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 5, 8);

        runConcurrently(() -> {
            statService.accumulate(bizDate, "RULE", BigDecimal.ONE, new BigDecimal("11.52"), new BigDecimal("5.00"));
            return null;
        });

        List<StrategyDailyStat> stats = statMapper.selectList(Wrappers.<StrategyDailyStat>lambdaQuery()
                .eq(StrategyDailyStat::getBizDate, bizDate));
        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).getSoldQty()).isEqualByComparingTo("10");
        assertThat(stats.get(0).getRevenue()).isEqualByComparingTo("115.20");
        assertThat(stats.get(0).getGrossProfit()).isEqualByComparingTo("65.20");
        assertThat(stats.get(0).getScrapRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("并发超卖：库存 5，10 笔各卖 1，只有 5 笔真正扣减，库存不为负、批次转 SOLD_OUT")
    void concurrentFeedbackNeverOversells() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 5, 7);
        FruitItem item = ShopFixtures.item(itemMapper, "并发超卖桃", "并发测试类", "5.00", "12.00", 8);
        FruitBatch batch = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 8, "5", "5.00");

        List<SalesFeedbackResponse> responses = runConcurrently(() -> salesService.feedback(
                feedback(bizDate, item.getId(), batch.getId(), "1")));

        BigDecimal totalSold = responses.stream().map(SalesFeedbackResponse::soldQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalSold).isEqualByComparingTo("5");
        assertThat(responses.stream().filter(SalesFeedbackResponse::overSell).count()).isEqualTo(5);
        FruitBatch after = batchMapper.selectById(batch.getId());
        assertThat(after.getRemainQty()).isEqualByComparingTo("0");
        assertThat(after.getStatus()).isEqualTo("SOLD_OUT");
    }

    @Test
    @DisplayName("FIFO 并发跨批次：先到期的批次只剩 1 件，10 笔并发自动选批，卖空后转到下一批，没有一笔丢批次")
    void concurrentFifoMovesToNextBatchWhenFirstSellsOut() throws Exception {
        LocalDate bizDate = LocalDate.of(2033, 5, 9);
        FruitItem item = ShopFixtures.item(itemMapper, "并发跨批次橙", "并发测试类", "5.00", "12.00", 8);
        // 先到期：B1 只剩 1 件；后到期：B2 有 10 件
        FruitBatch first = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 8, "1", "5.00");
        FruitBatch second = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 8, "10", "5.00");

        List<SalesFeedbackResponse> responses = runConcurrently(() -> salesService.feedback(
                feedback(bizDate, item.getId(), null, "1")));

        assertThat(responses).hasSize(THREADS)
                .allSatisfy(r -> {
                    assertThat(r.batchId()).as("每一笔都要落到某个批次上，不能变成'无批次只记流水'").isNotNull();
                    assertThat(r.batchAutoPicked()).isTrue();
                    assertThat(r.soldQty()).isEqualByComparingTo("1");
                });
        assertThat(batchMapper.selectById(first.getId()).getRemainQty()).isEqualByComparingTo("0");
        assertThat(batchMapper.selectById(second.getId()).getRemainQty()).isEqualByComparingTo("1");
        assertThat(salesMapper.selectCount(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getItemId, item.getId()).isNull(SalesRecord::getBatchId))).isZero();
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

    private SalesFeedbackRequest feedback(LocalDate bizDate, Long itemId, Long batchId, String quantity) {
        SalesFeedbackRequest request = new SalesFeedbackRequest();
        request.setBizDate(bizDate);
        request.setItemId(itemId);
        request.setBatchId(batchId);
        request.setQuantity(new BigDecimal(quantity));
        request.setUnitPrice(new BigDecimal("11.52"));
        return request;
    }
}
