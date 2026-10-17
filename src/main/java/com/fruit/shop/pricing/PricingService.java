package com.fruit.shop.pricing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.BatchMetricsService;
import com.fruit.shop.batch.BatchService;
import com.fruit.shop.batch.domain.BatchMetrics;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.pricing.domain.PricingDecision;
import com.fruit.shop.pricing.domain.PricingSuggestion;
import com.fruit.shop.pricing.dto.DecisionRequest;
import com.fruit.shop.pricing.dto.DecisionResponse;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.dto.SuggestionGenerateResponse;
import com.fruit.shop.pricing.dto.SuggestionView;
import com.fruit.shop.pricing.mapper.PricingDecisionMapper;
import com.fruit.shop.pricing.mapper.PricingSuggestionMapper;
import com.fruit.shop.pricing.strategy.PricingContext;
import com.fruit.shop.pricing.strategy.PricingProposal;
import com.fruit.shop.pricing.strategy.PricingStrategy;
import com.fruit.shop.pricing.strategy.ModelPricingStrategy;
import com.fruit.shop.support.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定价建议与决策留痕（docs/01-技术方案.md 第 3 节第 3、4、7 条 + 第 5 节接口 4、5、6）。
 *
 * <p>五个关键约束在这里落地：
 * <ul>
 *   <li>只在候选折扣档里选、折扣 &lt;= 1.0（不许涨价）——即使某个策略实现越界，这里也会钳回来；</li>
 *   <li><b>最低折扣线</b>（docs/07 第 1 节 B，默认 0.60）：任何策略都不得低于该价格比，触底时
 *       {@code price_floor_hit=true}、{@code raw_discount_rate} 记录原始档位、reason 说明"触到店主设定的最低折扣线"；</li>
 *   <li><b>每日调价上限</b>（docs/07 第 1 节 B，默认 2 次/批次/天）：生成路径逐批次跳过并给出原因，
 *       店主改价路径直接 400；{@code adjust_seq} 如实记录"当天第几次调价"；</li>
 *   <li>每条建议必须有 reason（人话理由），reason 为空视为实现缺陷，直接拒绝写入；</li>
 *   <li>生成是幂等的：同一 bizDate+strategy+批次已有建议时不重复插入（幂等重跑不占调价次数）。</li>
 * </ul>
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /** 契约允许的策略取值。 */
    public static final List<String> STRATEGY_CODES = List.of("FIXED", "RULE", "MODEL");

    /** pricing_decision.reject_reason 是 VARCHAR(64)。 */
    public static final int REJECT_REASON_MAX_LENGTH = 64;

    private final Map<String, PricingStrategy> strategies;
    private final ModelPricingStrategy modelStrategy;
    private final PricingSuggestionMapper suggestionMapper;
    private final PricingDecisionMapper decisionMapper;
    private final FruitItemMapper itemMapper;
    private final FruitBatchMapper batchMapper;
    private final BatchService batchService;
    private final BatchMetricsService metricsService;
    private final PricingAdjustGuard adjustGuard;
    private final AppProperties props;

    public PricingService(List<PricingStrategy> strategyBeans,
                          PricingSuggestionMapper suggestionMapper,
                          PricingDecisionMapper decisionMapper,
                          FruitItemMapper itemMapper,
                          FruitBatchMapper batchMapper,
                          BatchService batchService,
                          BatchMetricsService metricsService,
                          PricingAdjustGuard adjustGuard,
                          AppProperties props) {
        Map<String, PricingStrategy> map = new LinkedHashMap<>();
        for (PricingStrategy strategy : strategyBeans) {
            map.put(strategy.code().toUpperCase(), strategy);
        }
        this.strategies = Map.copyOf(map);
        this.modelStrategy = strategyBeans.stream().filter(ModelPricingStrategy.class::isInstance)
                .map(ModelPricingStrategy.class::cast).findFirst().orElse(null);
        this.suggestionMapper = suggestionMapper;
        this.decisionMapper = decisionMapper;
        this.itemMapper = itemMapper;
        this.batchMapper = batchMapper;
        this.batchService = batchService;
        this.metricsService = metricsService;
        this.adjustGuard = adjustGuard;
        this.props = props;
    }

    /** 归一化策略码：null/空 走 app.pricing.active-strategy，非法值直接 400 报人话。 */
    public String normalizeStrategy(String raw) {
        String code = raw == null || raw.isBlank() ? props.activeStrategyCode() : raw.trim().toUpperCase();
        if (!strategies.containsKey(code)) {
            throw BusinessException.badRequest("strategy 取值必须是 " + String.join("/", STRATEGY_CODES) + " 之一，收到：" + raw);
        }
        return code;
    }

    /** 当前生效策略（效果看板要展示）。 */
    public String activeStrategy() {
        return normalizeStrategy(null);
    }

    /**
     * 4. 生成定价建议：对每个临期批次按策略给出候选档位里的折扣与人话理由。
     *
     * <p>本轮还会：套用最低折扣线并留痕、按"每日调价上限"拒绝超出次数的批次、
     * 把临期判定指标快照写进建议（事后可复核"为什么这天建议打折"）。
     */
    @Transactional
    public SuggestionGenerateResponse generate(SuggestionGenerateRequest request) {
        LocalDate bizDate = request.getBizDate() == null ? LocalDate.now() : request.getBizDate();
        String code = normalizeStrategy(request.getStrategy());
        int threshold = batchService.resolveThreshold(request.getDays());
        List<FruitBatch> batches = batchService.nearExpiryBatches(bizDate, threshold);
        Map<Long, FruitItem> items = batchService.loadItems(batches);
        PricingStrategy strategy = strategies.get(code);
        BigDecimal floor = props.minDiscountRate();
        int maxAdjust = adjustGuard.maxAdjustPerDay();
        // 整个业务日只查一次销量窗口：临期判定指标与建议快照共用同一份口径。
        BatchMetricsService.SalesWindow window = metricsService.loadWindow(bizDate);
        LocalDateTime now = LocalDateTime.now().withNano(0);

        int generated = 0;
        List<String> limited = new ArrayList<>();
        Map<String, Integer> effectiveCounts = new LinkedHashMap<>();
        // MODEL 当天早先回退了规则版、现在模型产物可用且覆盖了该批次（例如按提示重新导出了）：允许重新生成，算一次调价
        ModelPricingStrategy.ModelStatus modelStatus =
                "MODEL".equals(code) && modelStrategy != null ? modelStrategy.status(bizDate) : null;
        int replacedFallbacks = 0;
        Map<String, Integer> keptFallbacks = new LinkedHashMap<>();
        for (FruitBatch batch : batches) {
            // 幂等优先：同一天同一策略同一批次已经生成过 → 跳过，且不占"调价次数"。
            PricingSuggestion existing = existingSuggestion(bizDate, code, batch.getId());
            boolean replacing = false;
            if (existing != null) {
                boolean modelFallback = "MODEL".equals(code) && existing.getEffectiveStrategy() != null
                        && !"MODEL".equals(existing.getEffectiveStrategy());
                if (!modelFallback) {
                    continue;
                }
                if (modelStatus == null || !modelStatus.usable()) {
                    keptFallbacks.merge(modelStatus == null ? "模型策略未启用" : modelStatus.message(), 1, Integer::sum);
                    continue;
                }
                if (hasDecision(existing.getId())) {
                    keptFallbacks.merge("店主已对其做出决策", 1, Integer::sum);
                    continue;
                }
                replacing = true;
            }
            FruitItem item = items.get(batch.getItemId());
            if (item == null) {
                continue;
            }
            if (item.getBasePrice() == null || item.getBasePrice().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[pricing] 商品 {} 缺少原价，跳过批次 {}", item.getItemName(), batch.getBatchNo());
                continue;
            }
            int daysLeft = BatchService.daysLeft(bizDate, batch.getExpireDate());
            PricingContext context = new PricingContext(item, batch, bizDate, daysLeft,
                    batch.getRemainQty(), item.getBasePrice(), batch.getCostPrice(), item.getCategoryName());
            if (replacing && !modelStrategy.covers(context)) {
                keptFallbacks.merge("模型产物里仍没有该批次/单品", 1, Integer::sum);
                continue;
            }
            int adjustCount = adjustGuard.adjustCount(bizDate, batch.getId());
            if (adjustCount >= maxAdjust) {
                String reason = adjustGuard.limitReason(batch.getBatchNo(), bizDate, maxAdjust);
                limited.add(reason);
                if (replacing) {
                    keptFallbacks.merge("已达「同一批次每天最多调价 " + maxAdjust + " 次」的上限", 1, Integer::sum);
                }
                log.info("[pricing] 批次 {} 当天已调价 {} 次，达到上限，本次生成被拒绝", batch.getBatchNo(), adjustCount);
                continue;
            }
            int adjustSeq = adjustCount + 1;

            BatchMetrics metrics = metricsService.compute(bizDate, batch, window, now);

            PricingProposal proposal = strategy.propose(context);
            if (proposal == null || proposal.discountRate() == null) {
                log.warn("[pricing] 策略 {} 未给出折扣，跳过批次 {}", code, batch.getBatchNo());
                continue;
            }
            // 双保险：候选档 + 不许涨价（rule 3 / rule 4），任何策略越界都在这里被钳回来
            BigDecimal rate = PricingPolicy.toCandidate(proposal.discountRate());
            BigDecimal original = PricingPolicy.round2(item.getBasePrice());
            // v2 定价约束 1/2：最低折扣线（对所有策略生效，FIXED/RULE/MODEL 一视同仁）
            PricingPolicy.FloorClamp clamp = PricingPolicy.applyFloor(rate, floor);
            rate = clamp.rate();
            boolean floorHit = clamp.floorHit();
            BigDecimal rawRate = clamp.rawRate();
            // 策略自己已经在底线以内决策过（MODEL 导出时就这样做）：用它报告的"不受约束时的档位"留痕
            if (!floorHit && proposal.rawDiscountRate() != null && proposal.rawDiscountRate().compareTo(floor) < 0) {
                floorHit = true;
                rawRate = PricingPolicy.toCandidate(proposal.rawDiscountRate());
            }
            BigDecimal price = PricingPolicy.priceOf(original, rate);
            if (price.compareTo(original) > 0) {
                log.warn("[pricing] 策略 {} 给出高于原价的建议价，已钳到原价（批次 {}）", code, batch.getBatchNo());
                rate = PricingPolicy.MAX_DISCOUNT;
                price = original;
            }
            String reason = proposal.reason();
            if (reason == null || reason.isBlank()) {
                throw new BusinessException("策略 " + code + " 未给出 reason，违反「每条建议必须有人话理由」的约定");
            }

            StringBuilder fullReason = new StringBuilder(reason);
            if (floorHit) {
                fullReason.append("；已触到店主设定的最低折扣线 ")
                        .append(PricingPolicy.discountLabel(floor))
                        .append("（app.pricing.min-discount-rate），本可以更低到 ")
                        .append(PricingPolicy.discountLabel(rawRate))
                        .append("，被底线拦住");
            }
            if (adjustSeq > 1) {
                fullReason.append("；本批次当天第 ").append(adjustSeq).append(" 次调价（上限 ")
                        .append(maxAdjust).append(" 次）");
            }
            fullReason.append("；临期判定：").append(metrics.reason());

            PricingSuggestion suggestion = new PricingSuggestion();
            suggestion.setBizDate(bizDate);
            suggestion.setItemId(item.getId());
            suggestion.setBatchId(batch.getId());
            suggestion.setDaysLeft(daysLeft);
            suggestion.setRemainQty(batch.getRemainQty());
            suggestion.setOriginalPrice(original);
            suggestion.setSuggestedPrice(price);
            suggestion.setDiscountRate(rate);
            suggestion.setStrategy(code);
            String effective = proposal.effectiveStrategy() == null ? code : proposal.effectiveStrategy();
            suggestion.setEffectiveStrategy(effective);
            effectiveCounts.merge(effective, 1, Integer::sum);
            suggestion.setExpectedQty(proposal.expectedQty());
            suggestion.setReason(PricingPolicy.reason(fullReason.toString()));
            suggestion.setPriceFloorHit(floorHit);
            suggestion.setRawDiscountRate(rawRate);
            suggestion.setAdjustSeq(adjustSeq);
            suggestion.setFreshness(metrics.freshness());
            suggestion.setEstimatedSellableDays(metrics.estimatedSellableDays());
            suggestion.setExpectedScrapQty(metrics.expectedScrapQty());
            suggestion.setCreatedAt(LocalDateTime.now());
            suggestionMapper.insert(suggestion);
            generated++;
            if (replacing) {
                replacedFallbacks++;
            }
        }
        String note = limited.isEmpty() ? null
                : "有 " + limited.size() + " 个批次因「同一批次每天最多调价 " + maxAdjust + " 次」被拒绝：" + limited.get(0);
        if (note != null) {
            log.info("[pricing] bizDate={} strategy={} 被每日调价上限拒绝 {} 个批次", bizDate, code, limited.size());
        }
        log.info("[pricing] bizDate={} strategy={} 临期批次 {} 个，新生成建议 {} 条，实际生效策略 {}",
                bizDate, code, batches.size(), generated, effectiveCounts);
        List<String> strategyNotes = new ArrayList<>();
        int fallback = generated - effectiveCounts.getOrDefault(code, 0);
        if (fallback > 0) {
            String breakdown = effectiveCounts.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue() + " 条")
                    .collect(java.util.stream.Collectors.joining("、"));
            strategyNotes.add("请求 " + code + "，其中 " + fallback + " 条实际由其它策略给出（" + breakdown
                    + "）；原因写在每条建议的理由里，效果看板按实际生效策略统计");
        }
        if (replacedFallbacks > 0) {
            strategyNotes.add("模型产物现已可用，" + replacedFallbacks + " 个早先回退规则版的批次已用模型重新生成（计一次调价）");
        }
        if (!keptFallbacks.isEmpty()) {
            int kept = keptFallbacks.values().stream().mapToInt(Integer::intValue).sum();
            String why = keptFallbacks.entrySet().stream()
                    .map(e -> e.getKey() + "（" + e.getValue() + " 个）")
                    .collect(java.util.stream.Collectors.joining("；"));
            strategyNotes.add(kept + " 个批次今天早先回退了规则版，本次未重新生成：" + why);
        }
        String strategyNote = strategyNotes.isEmpty() ? null : String.join("；", strategyNotes);
        return new SuggestionGenerateResponse(bizDate, code, generated, limited.size(), note,
                effectiveCounts, strategyNote);
    }

    /** 5. 查询建议：bizDate 不传时自动取库里最新一天；strategy 不传返回全部策略。 */
    public List<SuggestionView> query(LocalDate bizDate, String strategy) {
        String code = strategy == null || strategy.isBlank() ? null : normalizeStrategy(strategy);
        LocalDate date = bizDate;
        if (date == null) {
            PricingSuggestion latest = suggestionMapper.selectOne(Wrappers.<PricingSuggestion>lambdaQuery()
                    .orderByDesc(PricingSuggestion::getBizDate)
                    .orderByDesc(PricingSuggestion::getId)
                    .last("LIMIT 1"));
            if (latest == null) {
                return List.of();
            }
            date = latest.getBizDate();
        }

        LambdaQueryWrapper<PricingSuggestion> wrapper = Wrappers.<PricingSuggestion>lambdaQuery()
                .eq(PricingSuggestion::getBizDate, date)
                .eq(code != null, PricingSuggestion::getStrategy, code)
                .orderByAsc(PricingSuggestion::getDaysLeft)
                .orderByAsc(PricingSuggestion::getId);
        List<PricingSuggestion> all = suggestionMapper.selectList(wrapper);
        if (all.isEmpty()) {
            return List.of();
        }
        // 同一批次同一策略当天被重新生成过（MODEL 回退后用模型重做）：只展示最新的一条，旧的留在库里备查
        Map<String, Long> newestId = new HashMap<>();
        for (PricingSuggestion s : all) {
            newestId.merge(s.getBatchId() + "|" + s.getStrategy(), s.getId(), Math::max);
        }
        List<PricingSuggestion> suggestions = all.stream()
                .filter(s -> s.getId().equals(newestId.get(s.getBatchId() + "|" + s.getStrategy())))
                .toList();

        List<Long> itemIds = suggestions.stream().map(PricingSuggestion::getItemId).distinct().toList();
        List<Long> batchIds = suggestions.stream().map(PricingSuggestion::getBatchId).distinct().toList();
        Map<Long, FruitItem> items = new HashMap<>();
        itemMapper.selectBatchIds(itemIds).forEach(item -> items.put(item.getId(), item));
        Map<Long, FruitBatch> batches = new HashMap<>();
        batchMapper.selectBatchIds(batchIds).forEach(batch -> batches.put(batch.getId(), batch));
        Map<Long, PricingDecision> decisions = latestDecisions(suggestions.stream().map(PricingSuggestion::getId).toList());

        List<SuggestionView> views = new ArrayList<>(suggestions.size());
        for (PricingSuggestion s : suggestions) {
            FruitItem item = items.get(s.getItemId());
            FruitBatch batch = batches.get(s.getBatchId());
            PricingDecision decision = decisions.get(s.getId());
            views.add(new SuggestionView(
                    s.getId(), s.getBizDate(), s.getItemId(), item == null ? null : item.getItemName(),
                    s.getBatchId(), batch == null ? null : batch.getBatchNo(), s.getDaysLeft(), s.getRemainQty(),
                    s.getOriginalPrice(), s.getSuggestedPrice(), s.getDiscountRate(), s.getStrategy(),
                    s.getExpectedQty(), s.getReason(), decision != null,
                    decision == null ? null : decision.getFinalPrice(),
                    s.getPriceFloorHit(), s.getRawDiscountRate(), s.getAdjustSeq(),
                    s.getFreshness(), s.getEstimatedSellableDays(), s.getExpectedScrapQty(),
                    s.getEffectiveStrategy() == null ? s.getStrategy() : s.getEffectiveStrategy()));
        }
        return views;
    }

    /**
     * 6. 店主确认/改价：必须落 pricing_decision（人机协同的证据）。
     *
     * <p>改价同样受两个定价约束（docs/07 第 1 节 B）：不得低于最低折扣线、不得突破当日调价上限；
     * 不接受建议时把原因写进 {@code reject_reason}。
     */
    @Transactional
    public DecisionResponse decide(DecisionRequest request) {
        PricingSuggestion suggestion = suggestionMapper.selectById(request.getSuggestionId());
        if (suggestion == null) {
            throw BusinessException.notFound("定价建议不存在：suggestionId=" + request.getSuggestionId());
        }
        // 回退规则版的 MODEL 建议在模型可用后会被重新生成；旧页面上对旧建议的决策会被列表隐藏、却仍被推送取用
        Long newer = suggestionMapper.selectCount(Wrappers.<PricingSuggestion>lambdaQuery()
                .eq(PricingSuggestion::getBizDate, suggestion.getBizDate())
                .eq(PricingSuggestion::getStrategy, suggestion.getStrategy())
                .eq(PricingSuggestion::getBatchId, suggestion.getBatchId())
                .gt(PricingSuggestion::getId, suggestion.getId()));
        if (newer != null && newer > 0) {
            throw BusinessException.conflict("该建议已被重新生成（模型产物可用后替换了回退规则版的建议），请刷新页面后对最新建议决策");
        }
        BigDecimal suggestedPrice = PricingPolicy.round2(suggestion.getSuggestedPrice());
        BigDecimal finalPrice = request.getFinalPrice() == null
                ? suggestedPrice
                : PricingPolicy.round2(request.getFinalPrice());
        if (finalPrice == null || finalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("finalPrice 必须大于 0");
        }
        boolean accepted = request.getAccepted() != null
                ? request.getAccepted()
                : finalPrice.compareTo(suggestedPrice) == 0;
        boolean changed = finalPrice.compareTo(suggestedPrice) != 0;

        if (changed) {
            // 约束 1：手工改价也不得低于最低折扣线（边界闭区间：恰好等于底线可通过）。
            assertPriceAtOrAboveFloor(suggestion, finalPrice);
            // 约束 2：手工改价同样计入"每天最多调价 N 次"。
            FruitBatch batch = suggestion.getBatchId() == null ? null : batchMapper.selectById(suggestion.getBatchId());
            String batchLabel = batch == null ? ("#" + suggestion.getBatchId()) : batch.getBatchNo();
            adjustGuard.assertCanAdjust(batchLabel, suggestion.getBizDate(), suggestion.getBatchId());
        }

        String operator = request.getOperator() == null || request.getOperator().isBlank()
                ? "店主" : request.getOperator().trim();
        String reason = request.getReason() == null || request.getReason().isBlank()
                ? (accepted ? "采纳系统建议价" : "店主手动改价")
                : request.getReason().trim();
        String rejectReason = accepted ? null : rejectReason(request, reason);

        PricingDecision decision = new PricingDecision();
        decision.setSuggestionId(suggestion.getId());
        decision.setSuggestedPrice(suggestedPrice);
        decision.setFinalPrice(finalPrice);
        decision.setAccepted(accepted);
        decision.setOperator(operator);
        decision.setReason(reason);
        decision.setRejectReason(rejectReason);
        decision.setDecidedAt(LocalDateTime.now());
        decisionMapper.insert(decision);

        log.info("[pricing] 决策留痕 suggestionId={} suggested={} final={} accepted={} operator={} rejectReason={}",
                suggestion.getId(), suggestedPrice, finalPrice, accepted, operator, rejectReason);
        return new DecisionResponse(decision.getId(), accepted, finalPrice, suggestedPrice, operator, reason,
                decision.getDecidedAt(), changed, rejectReason);
    }

    // ------------------------------------------------------------------ 内部实现

    /** 最低折扣线校验：finalPrice ÷ 原价 不得低于配置底线，否则 400 并告诉店主最低可售价。 */
    private void assertPriceAtOrAboveFloor(PricingSuggestion suggestion, BigDecimal finalPrice) {
        BigDecimal floor = props.minDiscountRate();
        BigDecimal original = PricingPolicy.round2(suggestion.getOriginalPrice());
        BigDecimal rate = PricingPolicy.rateOf(finalPrice, original);
        if (rate == null) {
            return;
        }
        if (rate.compareTo(floor) < 0) {
            BigDecimal lowest = PricingPolicy.lowestPriceAtFloor(original, floor);
            throw BusinessException.badRequest("改价被拒绝：¥" + PricingPolicy.money(finalPrice)
                    + "（原价 ¥" + PricingPolicy.money(original) + "，相当于 " + PricingPolicy.priceRatioLabel(rate)
                    + "）低于店主设定的最低折扣线 " + PricingPolicy.discountLabel(floor)
                    + "（app.pricing.min-discount-rate）；本单最低可售 ¥" + PricingPolicy.money(lowest)
                    + "。要清库存请改批次到货量或等明天再调。");
        }
    }

    /** 不接受建议时的原因：显式 rejectReason 优先，否则用 reason 兜底（截断到列宽 64）。 */
    private static String rejectReason(DecisionRequest request, String reason) {
        String raw = request.getRejectReason() == null || request.getRejectReason().isBlank()
                ? reason
                : request.getRejectReason().trim();
        return PricingPolicy.truncate(raw, REJECT_REASON_MAX_LENGTH);
    }

    /** 建议 id → 最新一条决策（同一建议可能被改价多次，以 id 最大的为准）。 */
    private Map<Long, PricingDecision> latestDecisions(List<Long> suggestionIds) {
        Map<Long, PricingDecision> map = new HashMap<>();
        if (suggestionIds.isEmpty()) {
            return map;
        }
        List<PricingDecision> decisions = decisionMapper.selectList(Wrappers.<PricingDecision>lambdaQuery()
                .in(PricingDecision::getSuggestionId, suggestionIds)
                .orderByAsc(PricingDecision::getId));
        for (PricingDecision decision : decisions) {
            map.put(decision.getSuggestionId(), decision);
        }
        return map;
    }

    private PricingSuggestion existingSuggestion(LocalDate bizDate, String strategy, Long batchId) {
        return suggestionMapper.selectOne(Wrappers.<PricingSuggestion>lambdaQuery()
                .eq(PricingSuggestion::getBizDate, bizDate)
                .eq(PricingSuggestion::getStrategy, strategy)
                .eq(PricingSuggestion::getBatchId, batchId)
                .orderByDesc(PricingSuggestion::getId)
                .last("LIMIT 1"));
    }

    private boolean hasDecision(Long suggestionId) {
        return decisionMapper.selectCount(Wrappers.<PricingDecision>lambdaQuery()
                .eq(PricingDecision::getSuggestionId, suggestionId)) > 0;
    }
}
