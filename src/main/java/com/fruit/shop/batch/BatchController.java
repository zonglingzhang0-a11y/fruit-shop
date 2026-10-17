package com.fruit.shop.batch;

import com.fruit.shop.batch.dto.BatchCreateRequest;
import com.fruit.shop.batch.dto.BatchCreateResponse;
import com.fruit.shop.batch.dto.InStockBatchRow;
import com.fruit.shop.batch.dto.NearExpiryRow;
import com.fruit.shop.batch.dto.OnSaleRow;
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

/** 批次接口（docs/01-技术方案.md 第 5 节接口 2、3）。 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final OnSaleService onSaleService;

    public BatchController(BatchService batchService, OnSaleService onSaleService) {
        this.batchService = batchService;
        this.onSaleService = onSaleService;
    }

    /** 2. 登记到货批次。 */
    @PostMapping
    public ApiResult<BatchCreateResponse> create(@Valid @RequestBody BatchCreateRequest request) {
        return ApiResult.ok(batchService.create(request));
    }

    /** 3. 临期清单（days 默认取 app.pricing.near-expiry-days=2；含临期判定指标）。 */
    @GetMapping("/near-expiry")
    public ApiResult<List<NearExpiryRow>> nearExpiry(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) Integer days) {
        return ApiResult.ok(batchService.nearExpiry(bizDate, days));
    }

    /** 3b. "有风险"清单：临期清单里 at_risk=true 的行（预计可售天数 > 剩余天数）。只读。 */
    @GetMapping("/at-risk")
    public ApiResult<List<NearExpiryRow>> atRisk(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) Integer days) {
        return ApiResult.ok(batchService.atRiskList(bizDate, days));
    }

    /**
     * 3d. 某单品有库存的在售批次（销售回流录入页"选批次"用），顺序与回流不指定批次时的 FIFO 自动挑选一致。
     * {@code includeSoldOut=true}（退货用）时在后面追加最近的已售罄批次。只读。
     */
    @GetMapping("/in-stock")
    public ApiResult<List<InStockBatchRow>> inStock(
            @RequestParam Long itemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(defaultValue = "false") boolean includeSoldOut) {
        return ApiResult.ok(batchService.inStock(itemId, bizDate, includeSoldOut));
    }

    /**
     * 3c. 顾客端"当日特价"：只含**店主已确认价格**的批次（final_price 来自 pricing_decision），
     * 供 H5 特价页使用。只读；不返回任何会员/个人信息。
     */
    @GetMapping("/on-sale")
    public ApiResult<List<OnSaleRow>> onSale(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResult.ok(onSaleService.onSale(bizDate));
    }
}
