package com.fruit.shop.pricing;

import com.fruit.shop.pricing.dto.DecisionRequest;
import com.fruit.shop.pricing.dto.DecisionResponse;
import com.fruit.shop.pricing.dto.ModelInputResponse;
import com.fruit.shop.pricing.dto.SuggestionGenerateRequest;
import com.fruit.shop.pricing.dto.SuggestionGenerateResponse;
import com.fruit.shop.pricing.dto.SuggestionView;
import com.fruit.shop.pricing.strategy.ModelPricingStrategy;
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
import java.util.List;

/** 定价接口（docs/01-技术方案.md 第 5 节接口 4、5、6）。 */
@RestController
@RequestMapping("/api/pricing")
public class PricingController {

    private final PricingService pricingService;
    private final ModelInputService modelInputService;
    private final ModelPricingStrategy modelStrategy;

    public PricingController(PricingService pricingService, ModelInputService modelInputService,
                             ModelPricingStrategy modelStrategy) {
        this.pricingService = pricingService;
        this.modelInputService = modelInputService;
        this.modelStrategy = modelStrategy;
    }

    /** 4. 生成定价建议（strategy=FIXED/RULE/MODEL，不传用配置里的生效策略）。 */
    @PostMapping("/suggestions/generate")
    public ApiResult<SuggestionGenerateResponse> generate(@Valid @RequestBody(required = false) SuggestionGenerateRequest request) {
        SuggestionGenerateRequest payload = request == null ? new SuggestionGenerateRequest() : request;
        return ApiResult.ok(pricingService.generate(payload));
    }

    /** 5. 查询建议（联表带出 itemName/batchNo/是否已决策）。 */
    @GetMapping("/suggestions")
    public ApiResult<List<SuggestionView>> suggestions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) String strategy) {
        return ApiResult.ok(pricingService.query(bizDate, strategy));
    }

    /**
     * 模型输入快照：给 scripts/export_model_suggestions.py 取数（只读）。
     * 只含 bizDate 之前的成交，模型看不到当天及以后的结果。
     */
    @GetMapping("/model-input")
    public ApiResult<ModelInputResponse> modelInput(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) Integer historyDays) {
        return ApiResult.ok(modelInputService.snapshot(bizDate, historyDays));
    }

    /** 模型产物状态：选 MODEL 生成建议前先看产物在不在、是不是这个业务日的（不可用会回退规则版）。 */
    @GetMapping("/model-status")
    public ApiResult<ModelPricingStrategy.ModelStatus> modelStatus(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResult.ok(modelStrategy.status(bizDate));
    }

    /** 6. 店主确认/改价，落 pricing_decision。 */
    @PostMapping("/decisions")
    public ApiResult<DecisionResponse> decide(@Valid @RequestBody DecisionRequest request) {
        return ApiResult.ok(pricingService.decide(request));
    }
}
