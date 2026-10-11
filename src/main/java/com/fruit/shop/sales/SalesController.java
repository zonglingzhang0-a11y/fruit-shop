package com.fruit.shop.sales;

import com.fruit.shop.sales.dto.EffectResponse;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
import com.fruit.shop.support.ApiResult;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 销售与效果接口（docs/01-技术方案.md 第 5 节接口 11、12）。 */
@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final SalesService salesService;

    public SalesController(SalesService salesService) {
        this.salesService = salesService;
    }

    /** 11. 效果看板（from/to 不传默认最近 7 天；strategy 不传=ALL）。 */
    @GetMapping("/effect")
    public ApiResult<EffectResponse> effect(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String strategy) {
        return ApiResult.ok(salesService.effect(from, to, strategy));
    }

    /** 12. 销售回流：扣减批次剩余量，扣到 0 置 SOLD_OUT，并累加策略日统计。 */
    @PostMapping("/feedback")
    public ApiResult<SalesFeedbackResponse> feedback(@Valid @RequestBody SalesFeedbackRequest request) {
        return ApiResult.ok(salesService.feedback(request));
    }
}
