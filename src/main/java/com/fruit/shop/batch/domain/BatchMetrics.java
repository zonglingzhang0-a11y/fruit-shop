package com.fruit.shop.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 临期判定指标（docs/07-与参考架构的差异与补齐.md 第 1 节 A，对应参考架构《临期判定》表）。
 *
 * <p>公式链（写死在一处，接口/定时扫描/定价快照共用同一份口径）：
 * <ul>
 *   <li>{@code freshness = 剩余天数 ÷ 标准保质期}，0~1，越小越不新鲜；</li>
 *   <li>{@code dailyAvgQty = 近 7 天该单品销量 ÷ 有效营业天数}（无销量日按 0 计，不剔除）；
 *       样本不足 3 天时用「该品类同期日均 × 该单品近 3 天占比」兜底并置 {@code fallbackUsed=true}；</li>
 *   <li>{@code estimatedSellableDays = 剩余库存 ÷ 日均销量}（卖不动时为 {@code null}，表示"理论上卖不完"）；</li>
 *   <li>{@code plannedSaleQty = min(剩余库存, 日均销量 × 剩余天数)}；</li>
 *   <li>{@code expectedScrapQty = 剩余库存 − 预计销量}；</li>
 *   <li>{@code atRisk = 预计可售天数 > 剩余天数}（卖不动时视为有风险）。</li>
 * </ul>
 *
 * <p>口径要点：{@code freshness} 回答"离坏还有多久"，{@code atRisk} 回答"按现价卖不卖得完"，
 * 两者是不同维度，不存在换算关系。
 *
 * @param fallbackUsed {@code true} = 该行日均销量走了"品类兜底"口径（reason 里会注明）
 * @param reason       人话理由（含兜底口径说明），会写进临期清单接口与定价建议的 reason
 */
public record BatchMetrics(
        LocalDate bizDate,
        Long batchId,
        int daysLeft,
        int shelfLifeDays,
        BigDecimal freshness,
        BigDecimal dailyAvgQty,
        int sampleDays,
        int businessDays,
        boolean fallbackUsed,
        BigDecimal estimatedSellableDays,
        BigDecimal plannedSaleQty,
        BigDecimal expectedScrapQty,
        boolean atRisk,
        String reason,
        LocalDateTime computedAt) {
}
