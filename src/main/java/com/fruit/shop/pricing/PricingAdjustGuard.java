package com.fruit.shop.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.pricing.domain.PricingDecision;
import com.fruit.shop.pricing.domain.PricingSuggestion;
import com.fruit.shop.pricing.mapper.PricingDecisionMapper;
import com.fruit.shop.pricing.mapper.PricingSuggestionMapper;
import com.fruit.shop.support.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 每日调价上限（docs/07 第 1 节 B：同一批次每天最多调价 {@code app.pricing.max-adjust-per-day} 次，默认 2）。
 *
 * <p><b>"一次调价"的口径</b>：该批次当天**已经发生的调价次数** =
 * <ol>
 *   <li>当天为该批次生成的定价建议条数（每生成一条 = 提出一次新价格；同一天同批次同策略的重复生成是幂等的，
 *       在 {@code PricingService} 里先被跳过，不计入次数）；</li>
 *   <li>当天这些建议里**店主实际改了价**的决策条数（{@code final_price != suggested_price}）；
 *       按建议价成交（没改价）不算调价。</li>
 * </ol>
 * 第 {@code max+1} 次调价会被拒绝：生成路径逐批次跳过并在响应里给出原因，店主改价路径直接 400 报人话。
 *
 * <p>两条路径共用本类，避免"生成时拦住了、店主手工改价却能绕过"的漏洞。
 */
@Service
public class PricingAdjustGuard {

    private final PricingSuggestionMapper suggestionMapper;
    private final PricingDecisionMapper decisionMapper;
    private final AppProperties props;

    public PricingAdjustGuard(PricingSuggestionMapper suggestionMapper,
                              PricingDecisionMapper decisionMapper,
                              AppProperties props) {
        this.suggestionMapper = suggestionMapper;
        this.decisionMapper = decisionMapper;
        this.props = props;
    }

    /** 当天该批次已经发生的调价次数（建议数 + 店主改价次数）。 */
    public int adjustCount(LocalDate bizDate, Long batchId) {
        if (bizDate == null || batchId == null) {
            return 0;
        }
        List<PricingSuggestion> suggestions = suggestionMapper.selectList(Wrappers.<PricingSuggestion>lambdaQuery()
                .eq(PricingSuggestion::getBizDate, bizDate)
                .eq(PricingSuggestion::getBatchId, batchId));
        if (suggestions.isEmpty()) {
            return 0;
        }
        int count = suggestions.size();
        List<Long> suggestionIds = new ArrayList<>(suggestions.size());
        for (PricingSuggestion suggestion : suggestions) {
            suggestionIds.add(suggestion.getId());
        }
        List<PricingDecision> decisions = decisionMapper.selectList(Wrappers.<PricingDecision>lambdaQuery()
                .in(PricingDecision::getSuggestionId, suggestionIds));
        for (PricingDecision decision : decisions) {
            if (isPriceChange(decision)) {
                count++;
            }
        }
        return count;
    }

    /** 该批次当天还能不能再调一次价。 */
    public boolean canAdjust(LocalDate bizDate, Long batchId) {
        return adjustCount(bizDate, batchId) < maxAdjustPerDay();
    }

    /** 生成建议时用的序号：当天第几次调价（1/2/…），受上限约束。 */
    public int nextAdjustSeq(LocalDate bizDate, Long batchId) {
        return adjustCount(bizDate, batchId) + 1;
    }

    /** 上限值（带默认值兜底）。 */
    public int maxAdjustPerDay() {
        return props.maxAdjustPerDay();
    }

    /** 被上限拦住时的人话原因（生成路径写进响应、改价路径直接抛 400）。 */
    public String limitReason(String batchLabel, LocalDate bizDate, int max) {
        return "批次 " + batchLabel + " 在 " + bizDate + " 已经调价 " + max + " 次，达到「同一批次每天最多调价 "
                + max + " 次」的上限（app.pricing.max-adjust-per-day）；第 " + (max + 1)
                + " 次调价被拒绝，请明天再调。";
    }

    /** 改价路径的守卫：到上限直接抛 400（人话原因给店主看）。 */
    public void assertCanAdjust(String batchLabel, LocalDate bizDate, Long batchId) {
        int max = maxAdjustPerDay();
        int count = adjustCount(bizDate, batchId);
        if (count >= max) {
            throw BusinessException.badRequest(limitReason(batchLabel, bizDate, max));
        }
    }

    private static boolean isPriceChange(PricingDecision decision) {
        return decision.getFinalPrice() != null
                && decision.getSuggestedPrice() != null
                && decision.getFinalPrice().compareTo(decision.getSuggestedPrice()) != 0;
    }
}
