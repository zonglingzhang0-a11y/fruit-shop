package com.fruit.shop.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.BatchMetrics;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.BatchCreateRequest;
import com.fruit.shop.batch.dto.BatchCreateResponse;
import com.fruit.shop.batch.dto.NearExpiryRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.support.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 到货批次与临期台账。
 *
 * <p>业务规则（docs/01-技术方案.md 第 3 节第 1、2 条）：
 * <ul>
 *   <li>{@code days_left = expire_date - biz_date}，到货当天 days_left = shelf_life_days，
 *       所以 {@code expire_date = arrive_date + shelf_life_days}；</li>
 *   <li>{@code days_left <= near-expiry-days}（默认 2）进临期清单，按 days_left 升序。</li>
 * </ul>
 * 天数一律在 Java 侧用 {@link ChronoUnit#DAYS} 计算，不依赖数据库方言函数（H2/MySQL 口径一致）。
 */
@Service
public class BatchService {

    private static final DateTimeFormatter BATCH_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String STATUS_ON_SALE = "ON_SALE";

    private final FruitBatchMapper batchMapper;
    private final FruitItemMapper itemMapper;
    private final BatchMetricsService metricsService;
    private final AppProperties props;

    public BatchService(FruitBatchMapper batchMapper, FruitItemMapper itemMapper,
                        BatchMetricsService metricsService, AppProperties props) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.metricsService = metricsService;
        this.props = props;
    }

    /** 剩余可售天数：到期日 - 业务日（可为 0 或负数，表示今天到期/已过期）。 */
    public static int daysLeft(LocalDate bizDate, LocalDate expireDate) {
        return (int) ChronoUnit.DAYS.between(bizDate, expireDate);
    }

    /** 临期阈值：请求里带了就用请求的，否则用配置 app.pricing.near-expiry-days。 */
    public int resolveThreshold(Integer days) {
        return days == null ? props.nearExpiryDays() : days;
    }

    /** 登记入库：算 expire_date、生成 batch_no、落 fruit_batch。 */
    @Transactional
    public BatchCreateResponse create(BatchCreateRequest request) {
        FruitItem item = itemMapper.selectById(request.getItemId());
        if (item == null) {
            throw BusinessException.badRequest("单品不存在：itemId=" + request.getItemId() + "，请先在商品档案里登记");
        }
        int shelfLifeDays = request.getShelfLifeDays() != null
                ? request.getShelfLifeDays()
                : (item.getShelfLifeDays() == null ? 0 : item.getShelfLifeDays());
        LocalDate expireDate = request.getArriveDate().plusDays(shelfLifeDays);
        BigDecimal costPrice = request.getCostPrice() != null ? request.getCostPrice() : item.getCostPrice();
        if (costPrice == null) {
            throw BusinessException.badRequest("该商品没有参考成本价，请在请求里带上 costPrice");
        }

        String batchNo = request.getBatchNo() == null || request.getBatchNo().isBlank()
                ? nextBatchNo(item.getId(), request.getArriveDate())
                : request.getBatchNo().trim();
        if (batchNo.length() > 32) {
            throw BusinessException.badRequest("batchNo 最长 32 个字符");
        }
        if (existsBatchNo(batchNo)) {
            throw BusinessException.badRequest("批次号已存在：" + batchNo);
        }

        LocalDateTime now = LocalDateTime.now();
        FruitBatch batch = new FruitBatch();
        batch.setItemId(item.getId());
        batch.setBatchNo(batchNo);
        batch.setArriveDate(request.getArriveDate());
        batch.setExpireDate(expireDate);
        batch.setInitQty(request.getQty());
        batch.setRemainQty(request.getQty());
        batch.setCostPrice(costPrice);
        batch.setScrapQty(BigDecimal.ZERO);
        batch.setStatus(STATUS_ON_SALE);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchMapper.insert(batch);

        return new BatchCreateResponse(batch.getId(), batch.getBatchNo(), expireDate, item.getId(), item.getItemName(),
                batch.getArriveDate(), shelfLifeDays, batch.getInitQty(), batch.getRemainQty(), costPrice, batch.getStatus());
    }

    /** 临期清单：days_left &lt;= 阈值，按 days_left 升序（越急越靠前）。无数据返回空数组，不报错。 */
    public List<NearExpiryRow> nearExpiry(LocalDate bizDate, Integer days) {
        LocalDate biz = bizDate == null ? LocalDate.now() : bizDate;
        int threshold = resolveThreshold(days);
        List<FruitBatch> batches = nearExpiryBatches(biz, threshold);
        if (batches.isEmpty()) {
            return List.of();
        }
        Map<Long, FruitItem> items = loadItems(batches);
        // 临期判定指标：查询接口实时计算（不写库），口径与只读扫描落库的完全一致。
        BatchMetricsService.SalesWindow window = metricsService.loadWindow(biz);
        LocalDateTime computedAt = LocalDateTime.now().withNano(0);
        List<NearExpiryRow> rows = new ArrayList<>(batches.size());
        for (FruitBatch batch : batches) {
            FruitItem item = items.get(batch.getItemId());
            BatchMetrics metrics = metricsService.compute(biz, batch, window, computedAt);
            rows.add(new NearExpiryRow(
                    batch.getId(),
                    batch.getBatchNo(),
                    batch.getItemId(),
                    item == null ? null : item.getItemName(),
                    item == null ? null : item.getCategoryName(),
                    item == null ? null : item.getUnit(),
                    batch.getArriveDate(),
                    batch.getExpireDate(),
                    daysLeft(biz, batch.getExpireDate()),
                    batch.getRemainQty(),
                    batch.getCostPrice(),
                    item == null ? null : item.getBasePrice(),
                    item == null ? null : item.getLossRatePct(),
                    metrics.freshness(),
                    metrics.estimatedSellableDays(),
                    metrics.plannedSaleQty(),
                    metrics.expectedScrapQty(),
                    metrics.atRisk(),
                    metrics.computedAt(),
                    metrics.reason()));
        }
        return rows;
    }

    /**
     * "有风险"清单：临期清单里 {@code at_risk=true}（预计可售天数 &gt; 剩余天数）的行。
     * 口径与 {@link #nearExpiry} 完全一致，只是多了一层过滤；定时扫描刷新的是同一批 at_risk 标志。
     */
    public List<NearExpiryRow> atRiskList(LocalDate bizDate, Integer days) {
        return nearExpiry(bizDate, days).stream()
                .filter(row -> Boolean.TRUE.equals(row.atRisk()))
                .toList();
    }

    /** 临期批次实体（定价模块复用）。 */
    public List<FruitBatch> nearExpiryBatches(LocalDate bizDate, int threshold) {
        LocalDate biz = bizDate == null ? LocalDate.now() : bizDate;
        List<FruitBatch> batches = batchMapper.selectList(Wrappers.<FruitBatch>lambdaQuery()
                .eq(FruitBatch::getStatus, STATUS_ON_SALE)
                .gt(FruitBatch::getRemainQty, BigDecimal.ZERO)
                .le(FruitBatch::getExpireDate, biz.plusDays(threshold)));
        List<FruitBatch> filtered = new ArrayList<>();
        for (FruitBatch batch : batches) {
            if (daysLeft(biz, batch.getExpireDate()) <= threshold) {
                filtered.add(batch);
            }
        }
        filtered.sort(Comparator
                .comparingInt((FruitBatch b) -> daysLeft(biz, b.getExpireDate()))
                .thenComparing(FruitBatch::getExpireDate)
                .thenComparing(b -> b.getBatchNo() == null ? "" : b.getBatchNo()));
        return filtered;
    }

    public FruitBatch requireBatch(Long batchId) {
        FruitBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw BusinessException.notFound("批次不存在：batchId=" + batchId);
        }
        return batch;
    }

    public Map<Long, FruitItem> loadItems(List<FruitBatch> batches) {
        List<Long> itemIds = batches.stream().map(FruitBatch::getItemId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, FruitItem> map = new HashMap<>();
        if (itemIds.isEmpty()) {
            return map;
        }
        for (FruitItem item : itemMapper.selectBatchIds(itemIds)) {
            map.put(item.getId(), item);
        }
        return map;
    }

    /** 批次号：B + 到货日(yyyyMMdd) + 商品ID(4位) + 两位序号，例如 B20350101-0007-01。 */
    private String nextBatchNo(Long itemId, LocalDate arriveDate) {
        String prefix = "B" + arriveDate.format(BATCH_NO_DATE) + "-" + String.format("%04d", itemId) + "-";
        long seq = batchMapper.selectCount(Wrappers.<FruitBatch>lambdaQuery().likeRight(FruitBatch::getBatchNo, prefix)) + 1;
        String candidate = prefix + String.format("%02d", seq);
        while (existsBatchNo(candidate)) {
            seq++;
            candidate = prefix + String.format("%02d", seq);
        }
        return candidate;
    }

    private boolean existsBatchNo(String batchNo) {
        return batchMapper.selectCount(Wrappers.<FruitBatch>lambdaQuery().eq(FruitBatch::getBatchNo, batchNo)) > 0;
    }
}
