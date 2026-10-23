package com.fruit.shop.sales;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.pricing.domain.PricingSuggestion;
import com.fruit.shop.pricing.mapper.PricingSuggestionMapper;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 策略日统计的累加器（效果看板的数据源）。
 *
 * <p>为什么要有这一层：{@code strategy_daily_stat} 的口径是"某天某策略卖了多少、报废多少、赚了多少"，
 * 而销售回流只知道"某批次卖了多少"。两者之间的桥是 {@code pricing_suggestion}：
 * 批次在某天用的是哪条建议，就归到哪条建议的策略名下；推断不出来时宁可记 null，也不硬编策略（避免假数据）。
 *
 * <p>损耗率口径：{@code scrap_qty / (sold_qty + scrap_qty)}，即"报废量 / 进货量"。
 */
@Service
public class StrategyStatService {

    private static final Logger log = LoggerFactory.getLogger(StrategyStatService.class);

    private final StrategyDailyStatMapper statMapper;
    private final PricingSuggestionMapper suggestionMapper;

    public StrategyStatService(StrategyDailyStatMapper statMapper, PricingSuggestionMapper suggestionMapper) {
        this.statMapper = statMapper;
        this.suggestionMapper = suggestionMapper;
    }

    /**
     * 按批次（优先）或商品推断该笔销售归属的策略；推断不出来返回 null。
     * 归属到<b>实际给出价格的策略</b>（effective_strategy）：请求 MODEL 但回退规则版的建议算 RULE。
     */
    public String inferStrategy(Long batchId, Long itemId, LocalDate bizDate) {
        LocalDate day = bizDate == null ? LocalDate.now() : bizDate;
        PricingSuggestion suggestion = null;
        if (batchId != null) {
            suggestion = suggestionMapper.selectOne(Wrappers.<PricingSuggestion>lambdaQuery()
                    .eq(PricingSuggestion::getBatchId, batchId)
                    .le(PricingSuggestion::getBizDate, day)
                    .orderByDesc(PricingSuggestion::getBizDate)
                    .orderByDesc(PricingSuggestion::getId)
                    .last("LIMIT 1"));
        }
        if (suggestion == null && itemId != null) {
            suggestion = suggestionMapper.selectOne(Wrappers.<PricingSuggestion>lambdaQuery()
                    .eq(PricingSuggestion::getItemId, itemId)
                    .le(PricingSuggestion::getBizDate, day)
                    .orderByDesc(PricingSuggestion::getBizDate)
                    .orderByDesc(PricingSuggestion::getId)
                    .last("LIMIT 1"));
        }
        if (suggestion == null) {
            return null;
        }
        return suggestion.getEffectiveStrategy() != null ? suggestion.getEffectiveStrategy() : suggestion.getStrategy();
    }

    /**
     * 累加一条销售到 (bizDate, strategy) 日统计（唯一索引 uk_stat_date_strategy，所以是 upsert 语义）。
     *
     * @param soldQty 成交数量（退货为负）
     * @param revenue 营收（退货为负）
     * @param cost    成本（退货为负）
     * @return 是否写入了统计
     */
    @Transactional
    public boolean accumulate(LocalDate bizDate, String strategy, BigDecimal soldQty, BigDecimal revenue, BigDecimal cost) {
        if (bizDate == null || strategy == null || strategy.isBlank()
                || soldQty == null || soldQty.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        String code = strategy.trim().toUpperCase();
        // 一条语句完成"没有就插、有就累加"（INSERT ... ON DUPLICATE KEY UPDATE，H2 的 MySQL 模式同样支持）。
        // 不用"先 UPDATE、0 行再 INSERT、撞索引再 UPDATE"：那种写法在 MySQL 可重复读下两个首笔并发会互相等间隙锁而死锁
        statMapper.upsertAccumulate(bizDate, code, soldQty, nz(revenue), nz(cost));
        log.debug("[sales] 统计累加 bizDate={} strategy={} soldQty={} revenue={}", bizDate, code, soldQty, revenue);
        return true;
    }

    /** 损耗率 = 报废量 /（销量 + 报废量）。 */
    public static BigDecimal scrapRate(BigDecimal scrapQty, BigDecimal soldQty) {
        BigDecimal scrap = nz(scrapQty);
        BigDecimal sold = nz(soldQty);
        BigDecimal denominator = scrap.add(sold);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return scrap.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
