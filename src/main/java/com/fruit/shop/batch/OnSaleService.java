package com.fruit.shop.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.domain.BatchMetrics;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.OnSaleRow;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.PricingPolicy;
import com.fruit.shop.pricing.domain.PricingDecision;
import com.fruit.shop.pricing.domain.PricingSuggestion;
import com.fruit.shop.pricing.mapper.PricingDecisionMapper;
import com.fruit.shop.pricing.mapper.PricingSuggestionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 顾客端"当日特价"（H5 特价页数据源，docs/07 第 6 项）。
 *
 * <p><b>三条硬规则</b>：
 * <ol>
 *   <li>只返回**店主已经做过决策**的批次：该批次当天存在 {@code pricing_decision} 记录；
 *       没有决策的一律不返回，无论系统建议价多低——未经店主确认的价格不能对外露出；</li>
 *   <li>{@code finalPrice} 取 {@code pricing_decision.final_price}（店主实际定价），
 *       店主改过价时顾客看到改后的价；同一天同批次有多条建议/多次决策时取**最新的一条决策**；</li>
 *   <li>只返回 {@code status='ON_SALE'} 且 {@code remain_qty > 0} 的批次；接口只读，不写任何表。</li>
 * </ol>
 */
@Service
public class OnSaleService {

    private static final Logger log = LoggerFactory.getLogger(OnSaleService.class);

    private static final String STATUS_ON_SALE = "ON_SALE";

    private final FruitBatchMapper batchMapper;
    private final FruitItemMapper itemMapper;
    private final PricingSuggestionMapper suggestionMapper;
    private final PricingDecisionMapper decisionMapper;
    private final BatchMetricsService metricsService;
    private final JdbcTemplate jdbcTemplate;

    public OnSaleService(FruitBatchMapper batchMapper,
                         FruitItemMapper itemMapper,
                         PricingSuggestionMapper suggestionMapper,
                         PricingDecisionMapper decisionMapper,
                         BatchMetricsService metricsService,
                         JdbcTemplate jdbcTemplate) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.suggestionMapper = suggestionMapper;
        this.decisionMapper = decisionMapper;
        this.metricsService = metricsService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 当日特价清单。
     *
     * @param bizDate 业务日；不传时取"最近有店主决策的一天"（没有决策时退化为最新建议日；都没有则返回空数组）
     */
    public List<OnSaleRow> onSale(LocalDate bizDate) {
        LocalDate biz = bizDate == null ? latestDecidedDate() : bizDate;
        if (biz == null) {
            return List.of();
        }
        List<PricingSuggestion> suggestions = suggestionMapper.selectList(Wrappers.<PricingSuggestion>lambdaQuery()
                .eq(PricingSuggestion::getBizDate, biz));
        if (suggestions.isEmpty()) {
            return List.of();
        }
        Map<Long, PricingSuggestion> suggestionById = new HashMap<>();
        for (PricingSuggestion suggestion : suggestions) {
            suggestionById.put(suggestion.getId(), suggestion);
        }
        List<PricingDecision> decisions = decisionMapper.selectList(Wrappers.<PricingDecision>lambdaQuery()
                .in(PricingDecision::getSuggestionId, new ArrayList<>(suggestionById.keySet()))
                .orderByAsc(PricingDecision::getId));
        if (decisions.isEmpty()) {
            log.debug("[on-sale] {} 还没有任何店主决策，顾客端不展示价格", biz);
            return List.of();
        }

        // 同一批次当天可能有多条建议、每条建议又可能被改价多次：以"决策 id 最大"的那条为准（店主最后定的价）。
        Map<Long, PricingDecision> latestByBatch = new HashMap<>();
        for (PricingDecision decision : decisions) {
            PricingSuggestion suggestion = suggestionById.get(decision.getSuggestionId());
            if (suggestion == null || suggestion.getBatchId() == null) {
                continue;
            }
            PricingDecision current = latestByBatch.get(suggestion.getBatchId());
            if (current == null || decision.getId() > current.getId()) {
                latestByBatch.put(suggestion.getBatchId(), decision);
            }
        }
        if (latestByBatch.isEmpty()) {
            return List.of();
        }

        Map<Long, FruitBatch> batches = new HashMap<>();
        batchMapper.selectBatchIds(new ArrayList<>(latestByBatch.keySet()))
                .forEach(batch -> batches.put(batch.getId(), batch));
        Map<Long, FruitItem> items = new HashMap<>();
        List<Long> itemIds = batches.values().stream().map(FruitBatch::getItemId).distinct().toList();
        if (!itemIds.isEmpty()) {
            itemMapper.selectBatchIds(itemIds).forEach(item -> items.put(item.getId(), item));
        }

        BatchMetricsService.SalesWindow window = metricsService.loadWindow(biz);
        LocalDateTime computedAt = LocalDateTime.now().withNano(0);
        List<OnSaleRow> rows = new ArrayList<>();
        for (Map.Entry<Long, PricingDecision> entry : latestByBatch.entrySet()) {
            FruitBatch batch = batches.get(entry.getKey());
            if (batch == null || !STATUS_ON_SALE.equals(batch.getStatus())) {
                continue;
            }
            if (batch.getRemainQty() == null || batch.getRemainQty().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FruitItem item = items.get(batch.getItemId());
            if (item == null || item.getBasePrice() == null) {
                continue;
            }
            PricingDecision decision = entry.getValue();
            PricingSuggestion suggestion = suggestionById.get(decision.getSuggestionId());
            BigDecimal originalPrice = PricingPolicy.round2(item.getBasePrice());
            BigDecimal finalPrice = PricingPolicy.round2(decision.getFinalPrice());
            BigDecimal rate = PricingPolicy.rateOf(finalPrice, originalPrice);
            BatchMetrics metrics = metricsService.compute(biz, batch, window, computedAt);
            rows.add(new OnSaleRow(
                    batch.getId(),
                    batch.getBatchNo(),
                    batch.getItemId(),
                    item.getItemName(),
                    item.getCategoryName(),
                    item.getUnit(),
                    originalPrice,
                    finalPrice,
                    rate,
                    PricingPolicy.priceRatioLabel(rate),
                    BatchService.daysLeft(biz, batch.getExpireDate()),
                    batch.getExpireDate(),
                    metrics.freshness(),
                    metrics.atRisk(),
                    batch.getRemainQty(),
                    decision.getDecidedAt(),
                    suggestion == null ? null : suggestion.getId(),
                    decision.getId()));
        }
        rows.sort(Comparator
                .comparing(OnSaleRow::daysLeft, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(OnSaleRow::batchId));
        log.debug("[on-sale] {} 顾客端特价 {} 条（仅含店主已确认的批次）", biz, rows.size());
        return rows;
    }

    /**
     * 最近有店主决策的业务日：顾客端缺省日期用它（而不是"最新建议日"），
     * 否则会出现"页面显示最新一天、但那天的价格店主还没确认"的空页面。
     */
    public LocalDate latestDecidedDate() {
        LocalDate decided = jdbcTemplate.queryForObject(
                "SELECT MAX(s.biz_date) FROM pricing_suggestion s"
                        + " WHERE EXISTS (SELECT 1 FROM pricing_decision d WHERE d.suggestion_id = s.id)",
                LocalDate.class);
        if (decided != null) {
            return decided;
        }
        PricingSuggestion latest = suggestionMapper.selectOne(Wrappers.<PricingSuggestion>lambdaQuery()
                .orderByDesc(PricingSuggestion::getBizDate)
                .orderByDesc(PricingSuggestion::getId)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getBizDate();
    }
}
