package com.fruit.shop.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.BatchMetrics;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.traffic.domain.TrafficHourly;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 临期判定指标的计算与落库（docs/07 第 1 节 A）。
 *
 * <p>三个使用场景共用同一份公式，保证"接口上屏的数字"和"落库的数字"永远一致：
 * <ol>
 *   <li>临期清单接口：{@link #compute} 实时计算（查询接口不写库）；</li>
 *   <li>只读定时扫描：{@code NearExpiryScanService} 批量计算后 {@link #saveMetrics} 落
 *       {@code freshness / estimated_sellable_days / planned_sale_qty / expected_scrap_qty / at_risk / metrics_updated_at}；</li>
 *   <li>定价建议：把指标快照写进 {@code pricing_suggestion}，事后可复核"为什么这天建议打折"。</li>
 * </ol>
 *
 * <p><b>日均销量口径（答辩会被问）</b>：近 7 天该单品销量 ÷ 有效营业天数。
 * <ul>
 *   <li>窗口 = {@code [bizDate-7, bizDate-1]}（不含业务日当天，避免用"当天未结算的销量"参与测算）；</li>
 *   <li>有效营业天数 = 窗口内有客流记录（{@code shop_traffic_hourly}）的天数：闭店日不进分母，
 *       但"营业却没卖出去"的日子按 0 计入（不剔除），避免把"卖不动"美化成"没数据"；
 *       窗口内完全没有客流记录时按 7 天营业处理（不影响无客流数据的历史区间）；</li>
 *   <li>样本不足 3 天（近 7 天有销量的天数 &lt; 3）时，改用
 *       「该品类同期日均 × 该单品近 3 天占比」兜底，并在 reason 里注明这是兜底口径。</li>
 * </ul>
 *
 * <p>退货按负销量计入（{@code sales_record.is_return=true} 的数量在净销量里做减项）。
 */
@Service
public class BatchMetricsService {

    private static final Logger log = LoggerFactory.getLogger(BatchMetricsService.class);

    /** 日均销量窗口（天）。 */
    public static final int WINDOW_DAYS = 7;

    /** 兜底口径里"该单品近 N 天占比"的短窗口（天）。 */
    public static final int SHORT_WINDOW_DAYS = 3;

    /** 样本天数下限：近 7 天有销量的天数小于该值即视为样本不足。 */
    public static final int MIN_SAMPLE_DAYS = 3;

    /** 品类缺失时的归并键（理论上不会出现，商品档案是必填品类）。 */
    private static final String UNKNOWN_CATEGORY = "";

    private static final BigDecimal ZERO_QTY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final FruitBatchMapper batchMapper;
    private final FruitItemMapper itemMapper;
    private final SalesRecordMapper salesRecordMapper;
    private final TrafficHourlyMapper trafficHourlyMapper;

    public BatchMetricsService(FruitBatchMapper batchMapper,
                               FruitItemMapper itemMapper,
                               SalesRecordMapper salesRecordMapper,
                               TrafficHourlyMapper trafficHourlyMapper) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.salesRecordMapper = salesRecordMapper;
        this.trafficHourlyMapper = trafficHourlyMapper;
    }

    /** 计算单个批次的指标（自取窗口，适合单批次调用/单测）。 */
    public BatchMetrics compute(LocalDate bizDate, FruitBatch batch) {
        return compute(bizDate, batch, loadWindow(bizDate), LocalDateTime.now().withNano(0));
    }

    /**
     * 计算单个批次的指标（复用已加载的窗口；同一个业务日扫描多个批次时只查一次库）。
     */
    public BatchMetrics compute(LocalDate bizDate, FruitBatch batch, SalesWindow window, LocalDateTime computedAt) {
        LocalDate biz = bizDate == null ? LocalDate.now() : bizDate;
        LocalDate expireDate = batch.getExpireDate();
        int daysLeft = expireDate == null ? 0 : BatchService.daysLeft(biz, expireDate);
        int shelfLifeDays = shelfLifeDays(batch);
        BigDecimal remain = batch.getRemainQty() == null ? ZERO_QTY : batch.getRemainQty();

        BigDecimal sold7 = window.itemSold(batch.getItemId());
        int sampleDays = window.itemSaleDays(batch.getItemId());
        int businessDays = window.businessDays();

        BigDecimal dailyAvg;
        boolean fallbackUsed = sampleDays < MIN_SAMPLE_DAYS;
        String basis;
        if (fallbackUsed) {
            String category = window.categoryOf(batch.getItemId());
            BigDecimal categoryDaily = divide(window.categorySold(category), businessDays, 4);
            BigDecimal itemShort = window.itemSoldShort(batch.getItemId());
            BigDecimal categoryShort = window.categorySoldShort(category);
            BigDecimal share = categoryShort.signum() > 0
                    ? itemShort.divide(categoryShort, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            dailyAvg = categoryDaily.multiply(share).setScale(4, RoundingMode.HALF_UP);
            basis = "样本不足 3 天（近 7 天仅 " + sampleDays + " 天有销量），日均销量按兜底口径"
                    + "「该品类同期日均 × 该单品近 3 天占比」估算：品类日均 " + qty(categoryDaily)
                    + " × 占比 " + share.stripTrailingZeros().toPlainString() + " = " + qty(dailyAvg);
        } else {
            dailyAvg = divide(sold7, businessDays, 4);
            basis = "近 7 天日均销量 " + qty(dailyAvg) + "（该单品近 7 天净销量 " + qty(sold7)
                    + " ÷ 有效营业天数 " + businessDays + "，无销量的营业日按 0 计）";
        }
        if (dailyAvg.signum() < 0) {
            dailyAvg = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal freshness = freshness(daysLeft, shelfLifeDays);
        BigDecimal estimatedSellableDays = dailyAvg.signum() > 0
                ? remain.divide(dailyAvg, 3, RoundingMode.HALF_UP)
                : null;
        BigDecimal plannedSaleQty = dailyAvg.multiply(BigDecimal.valueOf(Math.max(daysLeft, 0)))
                .setScale(2, RoundingMode.HALF_UP);
        if (plannedSaleQty.compareTo(remain) > 0) {
            plannedSaleQty = remain.setScale(2, RoundingMode.HALF_UP);
        }
        if (plannedSaleQty.signum() < 0) {
            plannedSaleQty = ZERO_QTY;
        }
        BigDecimal expectedScrapQty = remain.subtract(plannedSaleQty).setScale(2, RoundingMode.HALF_UP);
        if (expectedScrapQty.signum() < 0) {
            expectedScrapQty = ZERO_QTY;
        }
        boolean atRisk = dailyAvg.signum() <= 0
                ? remain.signum() > 0
                : estimatedSellableDays.compareTo(BigDecimal.valueOf(daysLeft)) > 0;

        String reason = reason(basis, remain, dailyAvg, daysLeft, shelfLifeDays, freshness,
                estimatedSellableDays, plannedSaleQty, expectedScrapQty, atRisk);
        return new BatchMetrics(biz, batch.getId(), daysLeft, shelfLifeDays, freshness, dailyAvg, sampleDays,
                businessDays, fallbackUsed, estimatedSellableDays, plannedSaleQty, expectedScrapQty, atRisk,
                reason, computedAt);
    }

    /**
     * 把指标落库到 {@code fruit_batch} 的派生列。
     *
     * <p>刻意只写"计算出来的字段"：不改 {@code remain_qty / status / scrap_qty} 等业务状态
     * （docs/07 第 2 节：定时任务只写派生指标，所有业务状态变更必须由显式接口触发）。
     * 用 UpdateWrapper 而不是 updateById，是为了让"卖不动 → 预计可售天数"这类情况能把旧值显式置空。
     */
    public void saveMetrics(FruitBatch batch, BatchMetrics metrics) {
        batchMapper.update(null, Wrappers.<FruitBatch>lambdaUpdate()
                .eq(FruitBatch::getId, batch.getId())
                .set(FruitBatch::getFreshness, metrics.freshness())
                .set(FruitBatch::getEstimatedSellableDays, metrics.estimatedSellableDays())
                .set(FruitBatch::getPlannedSaleQty, metrics.plannedSaleQty())
                .set(FruitBatch::getExpectedScrapQty, metrics.expectedScrapQty())
                .set(FruitBatch::getAtRisk, metrics.atRisk())
                .set(FruitBatch::getMetricsUpdatedAt, metrics.computedAt()));
    }

    /** 保质期（天）= 到期日 − 到货日；缺字段时退化为 0（freshness 随之为 0）。 */
    public static int shelfLifeDays(FruitBatch batch) {
        if (batch == null || batch.getArriveDate() == null || batch.getExpireDate() == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(batch.getArriveDate(), batch.getExpireDate());
    }

    /** 新鲜度 = 剩余天数 ÷ 标准保质期，钳在 0~1；保质期缺失时给 0（最不新鲜，宁可提醒店主）。 */
    public static BigDecimal freshness(int daysLeft, int shelfLifeDays) {
        if (shelfLifeDays <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal value = BigDecimal.valueOf(Math.max(daysLeft, 0))
                .divide(BigDecimal.valueOf(shelfLifeDays), 4, RoundingMode.HALF_UP);
        return value.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE.setScale(4) : value;
    }

    /**
     * 加载近 7 天销量窗口（一次查询覆盖所有批次；品类口径需要窗口内出现过的所有单品档案）。
     *
     * @param bizDate 业务日；窗口 = [bizDate-7, bizDate-1]
     */
    public SalesWindow loadWindow(LocalDate bizDate) {
        LocalDate biz = bizDate == null ? LocalDate.now() : bizDate;
        LocalDate to = biz.minusDays(1);
        LocalDate from = biz.minusDays(WINDOW_DAYS);
        LocalDate shortFrom = biz.minusDays(SHORT_WINDOW_DAYS);

        List<SalesRecord> records = salesRecordMapper.selectList(Wrappers.<SalesRecord>lambdaQuery()
                .ge(SalesRecord::getBizDate, from)
                .le(SalesRecord::getBizDate, to));

        SalesWindow window = new SalesWindow(from, to, shortFrom);
        Map<Long, Set<LocalDate>> saleDays = new HashMap<>();
        Set<Long> itemIds = new HashSet<>();
        for (SalesRecord record : records) {
            if (record.getItemId() == null || record.getBizDate() == null) {
                continue;
            }
            BigDecimal qty = signedQty(record);
            window.addItemSold(record.getItemId(), qty);
            saleDays.computeIfAbsent(record.getItemId(), key -> new HashSet<>()).add(record.getBizDate());
            if (!record.getBizDate().isBefore(shortFrom)) {
                window.addItemSoldShort(record.getItemId(), qty);
            }
            itemIds.add(record.getItemId());
        }
        for (Map.Entry<Long, Set<LocalDate>> entry : saleDays.entrySet()) {
            window.putItemSaleDays(entry.getKey(), entry.getValue().size());
        }
        if (!itemIds.isEmpty()) {
            for (FruitItem item : itemMapper.selectBatchIds(new ArrayList<>(itemIds))) {
                window.putItemCategory(item.getId(), item.getCategoryName());
            }
        }
        for (SalesRecord record : records) {
            if (record.getItemId() == null || record.getBizDate() == null) {
                continue;
            }
            String category = window.categoryOf(record.getItemId());
            BigDecimal qty = signedQty(record);
            window.addCategorySold(category, qty);
            if (!record.getBizDate().isBefore(shortFrom)) {
                window.addCategorySoldShort(category, qty);
            }
        }
        window.setBusinessDays(businessDays(from, to));
        return window;
    }

    /**
     * 有效营业天数：窗口内有客流记录的天数；完全没有客流记录时按整窗营业处理（7 天）。
     * 见类注释的日均销量口径说明。
     */
    public int businessDays(LocalDate from, LocalDate to) {
        List<TrafficHourly> traffic = trafficHourlyMapper.selectList(Wrappers.<TrafficHourly>lambdaQuery()
                .ge(TrafficHourly::getStatDate, from)
                .le(TrafficHourly::getStatDate, to));
        Set<LocalDate> openDays = new HashSet<>();
        for (TrafficHourly row : traffic) {
            if (row.getStatDate() != null) {
                openDays.add(row.getStatDate());
            }
        }
        if (openDays.isEmpty()) {
            return WINDOW_DAYS;
        }
        return Math.min(openDays.size(), WINDOW_DAYS);
    }

    // ------------------------------------------------------------------ 内部实现

    private String reason(String basis, BigDecimal remain, BigDecimal dailyAvg, int daysLeft, int shelfLifeDays,
                          BigDecimal freshness, BigDecimal estimatedSellableDays, BigDecimal plannedSaleQty,
                          BigDecimal expectedScrapQty, boolean atRisk) {
        StringBuilder text = new StringBuilder(basis);
        text.append("；剩余 ").append(daysLeft).append(" 天、标准保质期 ").append(shelfLifeDays)
                .append(" 天 → 新鲜度 ").append(qty(freshness))
                .append("；现存 ").append(qty(remain));
        if (dailyAvg.signum() > 0) {
            text.append("，按日均 ").append(qty(dailyAvg)).append(" 预计可售 ")
                    .append(qty(estimatedSellableDays)).append(" 天");
        } else {
            text.append("，近 7 天没有净销量（卖不动），预计可售天数视为无穷");
        }
        if (atRisk) {
            text.append(" > 剩余 ").append(daysLeft).append(" 天，判为有风险：到期前预计只能卖 ")
                    .append(qty(plannedSaleQty)).append("、预计报废 ").append(qty(expectedScrapQty));
        } else {
            text.append("，不超过剩余 ").append(daysLeft).append(" 天，暂无风险：到期前预计卖 ")
                    .append(qty(plannedSaleQty)).append("、预计报废 ").append(qty(expectedScrapQty));
        }
        text.append("。");
        String result = text.toString();
        if (result.length() > 240) {
            result = result.substring(0, 240);
        }
        return result;
    }

    private static BigDecimal signedQty(SalesRecord record) {
        BigDecimal qty = record.getQuantity() == null ? BigDecimal.ZERO : record.getQuantity();
        return Boolean.TRUE.equals(record.getIsReturn()) ? qty.negate() : qty;
    }

    private static BigDecimal divide(BigDecimal value, int divisor, int scale) {
        if (divisor <= 0) {
            divisor = WINDOW_DAYS;
        }
        return value.divide(BigDecimal.valueOf(divisor), scale, RoundingMode.HALF_UP);
    }

    private static String qty(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    /**
     * 近 7 天销量窗口聚合结果（可变累加、只读暴露）。同一个业务日的多个批次共用一份，
     * 避免"临期清单 2000 个批次 × 每个批次查一次库"。
     */
    public static final class SalesWindow {

        private final LocalDate from;
        private final LocalDate to;
        private final LocalDate shortFrom;
        private final Map<Long, BigDecimal> itemSold = new LinkedHashMap<>();
        private final Map<Long, BigDecimal> itemSoldShort = new LinkedHashMap<>();
        private final Map<Long, Integer> itemSaleDays = new HashMap<>();
        private final Map<Long, String> itemCategory = new HashMap<>();
        private final Map<String, BigDecimal> categorySold = new LinkedHashMap<>();
        private final Map<String, BigDecimal> categorySoldShort = new LinkedHashMap<>();
        private int businessDays = WINDOW_DAYS;

        SalesWindow(LocalDate from, LocalDate to, LocalDate shortFrom) {
            this.from = from;
            this.to = to;
            this.shortFrom = shortFrom;
        }

        /** 窗口区间（含两端）。 */
        public LocalDate from() {
            return from;
        }

        /** 窗口右端 = bizDate - 1。 */
        public LocalDate to() {
            return to;
        }

        /** 短窗口左端 = bizDate - 3。 */
        public LocalDate shortFrom() {
            return shortFrom;
        }

        public int businessDays() {
            return businessDays;
        }

        /** 该单品近 7 天净销量（退货按负数计入；无记录为 0）。 */
        public BigDecimal itemSold(Long itemId) {
            return orZero(itemId == null ? null : itemSold.get(itemId));
        }

        /** 该单品近 3 天净销量。 */
        public BigDecimal itemSoldShort(Long itemId) {
            return orZero(itemId == null ? null : itemSoldShort.get(itemId));
        }

        /** 该单品近 7 天有销量的天数（样本天数）。 */
        public int itemSaleDays(Long itemId) {
            Integer days = itemId == null ? null : itemSaleDays.get(itemId);
            return days == null ? 0 : days;
        }

        /** 该单品所属品类（商品档案缺失时归并到空品类）。 */
        public String categoryOf(Long itemId) {
            String category = itemId == null ? null : itemCategory.get(itemId);
            return category == null ? UNKNOWN_CATEGORY : category;
        }

        /** 该品类近 7 天净销量。 */
        public BigDecimal categorySold(String category) {
            return orZero(categorySold.get(category == null ? UNKNOWN_CATEGORY : category));
        }

        /** 该品类近 3 天净销量。 */
        public BigDecimal categorySoldShort(String category) {
            return orZero(categorySoldShort.get(category == null ? UNKNOWN_CATEGORY : category));
        }

        void addItemSold(Long itemId, BigDecimal qty) {
            itemSold.merge(itemId, qty, BigDecimal::add);
        }

        void addItemSoldShort(Long itemId, BigDecimal qty) {
            itemSoldShort.merge(itemId, qty, BigDecimal::add);
        }

        void putItemSaleDays(Long itemId, int days) {
            itemSaleDays.put(itemId, days);
        }

        void putItemCategory(Long itemId, String category) {
            itemCategory.put(itemId, category == null ? UNKNOWN_CATEGORY : category);
        }

        void addCategorySold(String category, BigDecimal qty) {
            categorySold.merge(category, qty, BigDecimal::add);
        }

        void addCategorySoldShort(String category, BigDecimal qty) {
            categorySoldShort.merge(category, qty, BigDecimal::add);
        }

        void setBusinessDays(int businessDays) {
            this.businessDays = businessDays <= 0 ? WINDOW_DAYS : Math.min(businessDays, WINDOW_DAYS);
        }

        private static BigDecimal orZero(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
