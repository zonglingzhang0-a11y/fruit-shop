package com.fruit.shop.batch;

import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.BatchScrapRequest;
import com.fruit.shop.batch.dto.BatchScrapResponse;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.pricing.PricingPolicy;
import com.fruit.shop.sales.StrategyStatService;
import com.fruit.shop.support.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 报废人工登记（FR-06）。
 *
 * <p>与"不许自动淘汰"（docs/04 §4.2）配套：系统任何定时任务都不改批次状态，过期、腐烂的货只能由人在这里登记。
 * 登记后：现存减少、累计报废量增加，整批报废的批次置 SCRAPPED（从临期清单、在售批次里消失）；
 * 报废量按"给这批定价的策略"计入当日策略统计的损耗（与销售回流的归属口径一致），效果看板的损耗率因此是真实登记的数。
 */
@Service
public class BatchScrapService {

    private static final Logger log = LoggerFactory.getLogger(BatchScrapService.class);
    private static final String STATUS_SCRAPPED = "SCRAPPED";
    private static final String DEFAULT_OPERATOR = "店员";

    private final FruitBatchMapper batchMapper;
    private final BatchService batchService;
    private final StrategyStatService statService;

    public BatchScrapService(FruitBatchMapper batchMapper, BatchService batchService, StrategyStatService statService) {
        this.batchMapper = batchMapper;
        this.batchService = batchService;
        this.statService = statService;
    }

    /** 读已提交 + 批次行锁：与销售回流同口径，并发的回流和报废在批次行上排队，不会互相覆盖现存。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchScrapResponse scrap(Long batchId, BatchScrapRequest request) {
        if (request.getBizDate() == null) {
            throw BusinessException.badRequest("bizDate 不能为空（yyyy-MM-dd）");
        }
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty()) {
            throw BusinessException.badRequest("报废必须写原因（例如：已过期、腐烂、磕碰）");
        }
        batchService.requireBatch(batchId);
        FruitBatch batch = batchMapper.selectByIdForUpdate(batchId);

        BigDecimal remain = batch.getRemainQty() == null ? BigDecimal.ZERO : batch.getRemainQty();
        if (remain.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("批次 " + batch.getBatchNo() + " 已没有现存（状态 " + batch.getStatus()
                    + "），无可报废数量");
        }
        BigDecimal qty = request.getQty() == null ? remain : request.getQty().setScale(2, RoundingMode.HALF_UP);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("报废数量必须大于 0（按两位小数计）");
        }
        if (qty.compareTo(remain) > 0) {
            throw BusinessException.badRequest("报废数量 " + PricingPolicy.qty(qty) + " 超过批次 " + batch.getBatchNo()
                    + " 的现存 " + PricingPolicy.qty(remain));
        }

        BigDecimal left = remain.subtract(qty);
        BigDecimal total = (batch.getScrapQty() == null ? BigDecimal.ZERO : batch.getScrapQty()).add(qty);
        String operator = request.getOperator() == null || request.getOperator().isBlank()
                ? DEFAULT_OPERATOR : request.getOperator().trim();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        batch.setRemainQty(left);
        batch.setScrapQty(total);
        if (left.compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(STATUS_SCRAPPED);
        }
        batch.setScrappedAt(now);
        batch.setScrapReason(reason);
        batch.setScrapOperator(operator);
        batch.setUpdatedAt(now);
        batchMapper.updateById(batch);

        String strategy = statService.inferStrategy(batch.getId(), batch.getItemId(), request.getBizDate());
        boolean statUpdated = statService.accumulateScrap(request.getBizDate(), strategy, qty);

        String message = "已登记报废 " + PricingPolicy.qty(qty) + "（" + reason + "，登记人 " + operator + "）；"
                + (STATUS_SCRAPPED.equals(batch.getStatus())
                ? "整批报废，批次已移出临期清单"
                : "部分报废，批次仍在售，现存 " + PricingPolicy.qty(left))
                + (statUpdated ? "；计入策略 " + strategy + " 的当日损耗" : "；该批次没有定价建议，未计入策略统计");
        log.info("[scrap] 批次 {} 报废 {}（{}，{}），剩余 {}，状态 {}", batch.getBatchNo(), qty, reason, operator, left,
                batch.getStatus());
        return new BatchScrapResponse(batch.getId(), batch.getBatchNo(), qty, total, left, batch.getStatus(),
                statUpdated ? strategy : null, statUpdated, message);
    }
}
