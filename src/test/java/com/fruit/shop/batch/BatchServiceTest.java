package com.fruit.shop.batch;

import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.BatchCreateRequest;
import com.fruit.shop.batch.dto.BatchCreateResponse;
import com.fruit.shop.batch.dto.NearExpiryRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次与临期判定测试。
 * 重点覆盖边界：临期阈值 days_left=2 进清单、days_left=3 不进清单（app.pricing.near-expiry-days 默认 2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchServiceTest {

    @Autowired
    private BatchService batchService;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Test
    @DisplayName("登记入库：expire_date = 到货日 + 保质期，batch_no 自动生成，初始 remain_qty = qty")
    void registerBatchComputesExpireDateAndAutoBatchNo() {
        FruitItem item = ShopFixtures.item(itemMapper, "测试富士苹果", "测试仁果类", "3.00", "6.00", 5);
        LocalDate arriveDate = LocalDate.of(2035, 1, 10);

        BatchCreateRequest request = new BatchCreateRequest();
        request.setItemId(item.getId());
        request.setArriveDate(arriveDate);
        request.setQty(new BigDecimal("12"));
        request.setCostPrice(new BigDecimal("3.20"));

        BatchCreateResponse response = batchService.create(request);

        assertThat(response.batchId()).isNotNull();
        assertThat(response.expireDate()).isEqualTo(arriveDate.plusDays(5));
        assertThat(response.batchNo()).startsWith("B20350110-").contains(String.format("%04d", item.getId()));

        FruitBatch stored = batchMapper.selectById(response.batchId());
        assertThat(stored.getExpireDate()).isEqualTo(arriveDate.plusDays(5));
        assertThat(stored.getInitQty()).isEqualByComparingTo("12");
        assertThat(stored.getRemainQty()).isEqualByComparingTo("12");
        assertThat(stored.getScrapQty()).isEqualByComparingTo("0");
        assertThat(stored.getCostPrice()).isEqualByComparingTo("3.20");
        assertThat(stored.getStatus()).isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("规则 1：到货当天 days_left = shelf_life_days")
    void daysLeftEqualsShelfLifeOnArrivalDay() {
        FruitItem item = ShopFixtures.item(itemMapper, "测试香蕉", "测试热带类", "2.00", "4.50", 4);
        LocalDate arriveDate = LocalDate.of(2035, 1, 11);

        BatchCreateRequest request = new BatchCreateRequest();
        request.setItemId(item.getId());
        request.setArriveDate(arriveDate);
        request.setQty(new BigDecimal("8"));

        BatchCreateResponse response = batchService.create(request);

        assertThat(BatchService.daysLeft(arriveDate, response.expireDate())).isEqualTo(4);
        assertThat(BatchService.daysLeft(response.expireDate(), response.expireDate())).isZero();
        assertThat(BatchService.daysLeft(response.expireDate().plusDays(1), response.expireDate())).isEqualTo(-1);
    }

    @Test
    @DisplayName("规则 1：不传 shelfLifeDays 时取商品档案的保质期")
    void shelfLifeFallsBackToItemProfile() {
        FruitItem item = ShopFixtures.item(itemMapper, "测试猕猴桃", "测试浆果类", "5.00", "12.00", 6);
        LocalDate arriveDate = LocalDate.of(2035, 1, 12);

        BatchCreateRequest request = new BatchCreateRequest();
        request.setItemId(item.getId());
        request.setArriveDate(arriveDate);
        request.setQty(new BigDecimal("3"));

        BatchCreateResponse response = batchService.create(request);

        assertThat(response.shelfLifeDays()).isEqualTo(6);
        assertThat(response.expireDate()).isEqualTo(arriveDate.plusDays(6));
    }

    @Test
    @DisplayName("规则 2 边界：days_left=2 进临期清单、days_left=3 不进，且按 days_left 升序")
    void nearExpiryBoundaryIncludesTwoButExcludesThree() {
        LocalDate bizDate = LocalDate.of(2035, 1, 20);
        FruitItem item = ShopFixtures.item(itemMapper, "测试临期果", "测试边界类", "4.00", "10.00", 10);

        // 已过期（daysLeft = -1）：最急，应排最前
        FruitBatch expired = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(6), 5, "5", "4.00");
        // days_left = 2：刚好压在阈值上，必须进清单
        FruitBatch twoDays = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "7", "4.00");
        // days_left = 3：超出阈值，不进清单
        FruitBatch threeDays = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 5, "9", "4.00");

        assertThat(BatchService.daysLeft(bizDate, expired.getExpireDate())).isEqualTo(-1);
        assertThat(BatchService.daysLeft(bizDate, twoDays.getExpireDate())).isEqualTo(2);
        assertThat(BatchService.daysLeft(bizDate, threeDays.getExpireDate())).isEqualTo(3);

        List<NearExpiryRow> rows = rowsOfItem(batchService.nearExpiry(bizDate, 2), item.getId());

        assertThat(rows).extracting(NearExpiryRow::batchId)
                .containsExactly(expired.getId(), twoDays.getId())
                .doesNotContain(threeDays.getId());
        assertThat(rows).extracting(NearExpiryRow::daysLeft).containsExactly(-1, 2);
        assertThat(rows).extracting(NearExpiryRow::daysLeft).isSorted();

        NearExpiryRow row = rows.get(1);
        assertThat(row.itemName()).isEqualTo("测试临期果");
        assertThat(row.batchNo()).isEqualTo(twoDays.getBatchNo());
        assertThat(row.remainQty()).isEqualByComparingTo("7");
        assertThat(row.basePrice()).isEqualByComparingTo("10.00");
        assertThat(row.lossRatePct()).isEqualByComparingTo("12.50");
        assertThat(row.unit()).isEqualTo("份");
    }

    @Test
    @DisplayName("阈值可被请求参数覆盖：days=3 时 days_left=3 也进清单")
    void thresholdCanBeOverriddenByRequest() {
        LocalDate bizDate = LocalDate.of(2035, 1, 21);
        FruitItem item = ShopFixtures.item(itemMapper, "测试三天果", "测试边界类", "4.00", "9.00", 10);
        FruitBatch threeDays = ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 5, "9", "4.00");

        List<NearExpiryRow> rows = rowsOfItem(batchService.nearExpiry(bizDate, 3), item.getId());

        assertThat(rows).extracting(NearExpiryRow::batchId).contains(threeDays.getId());
        assertThat(batchService.resolveThreshold(null)).isEqualTo(2);
        assertThat(batchService.resolveThreshold(5)).isEqualTo(5);
    }

    @Test
    @DisplayName("无数据也能启动：没有临期批次时返回空数组而不是报错")
    void nearExpiryReturnsEmptyListWhenNothingMatches() {
        // 1990 年的业务日不可能有任何批次临期（数据都是 2020 年以后的）
        List<NearExpiryRow> rows = batchService.nearExpiry(LocalDate.of(1990, 1, 1), 2);
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("登记不存在的单品 → 400 人话报错")
    void registerBatchRejectsUnknownItem() {
        BatchCreateRequest request = new BatchCreateRequest();
        request.setItemId(-999L);
        request.setArriveDate(LocalDate.of(2035, 1, 10));
        request.setQty(new BigDecimal("1"));

        assertThatThrownBy(() -> batchService.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单品不存在");
    }

    private List<NearExpiryRow> rowsOfItem(List<NearExpiryRow> rows, Long itemId) {
        return rows.stream()
                .filter(row -> itemId.equals(row.itemId()))
                .collect(Collectors.toList());
    }
}
