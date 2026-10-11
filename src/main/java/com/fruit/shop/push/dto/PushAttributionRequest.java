package com.fruit.shop.push.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;

/**
 * 归因重跑请求（接口 {@code POST /api/push/attribute}）：把窗口内该会员购买同一单品的销售记录重新匹配一遍。
 * 可重复调用且幂等——同一条销售记录不会重复挂到多条 log 上，已归因的不会重复计数。
 *
 * <p>三种范围，按优先级：{@code saleId}（单条）→ {@code bizDate}（单日）→ {@code from/to}（区间，默认最近 7 天）。
 */
@Data
public class PushAttributionRequest {

    /** 只重跑这一条销售记录。 */
    private Long saleId;

    /** 只重跑这一天的销售记录（biz_date）。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bizDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate from;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate to;

    /** 只看这个会员（可选过滤）。 */
    private Long memberId;
}
