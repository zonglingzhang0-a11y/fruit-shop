package com.fruit.shop.pricing.dto;

import java.time.LocalDate;

/**
 * 生成定价建议响应（前端约定字段：bizDate / strategy / generated）。
 *
 * @param generated             本次新生成条数；同一 bizDate+strategy+批次已存在的建议不会重复生成（幂等）
 * @param skippedByAdjustLimit  因"同一批次每天最多调价 N 次"（docs/07 第 1 节 B）被拒绝的批次数
 * @param adjustLimitNote       被拒绝的人话原因（没有拒绝时为 null），直接可展示给店主
 */
public record SuggestionGenerateResponse(
        LocalDate bizDate,
        String strategy,
        int generated,
        int skippedByAdjustLimit,
        String adjustLimitNote) {
}
