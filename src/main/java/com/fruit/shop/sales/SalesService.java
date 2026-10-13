package com.fruit.shop.sales;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.BatchService;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.ItemService;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.pricing.PricingPolicy;
import com.fruit.shop.pricing.PricingService;
import com.fruit.shop.push.PushEffectService;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import com.fruit.shop.sales.dto.EffectDailyRow;
import com.fruit.shop.sales.dto.EffectResponse;
import com.fruit.shop.sales.dto.SalesFeedbackRequest;
import com.fruit.shop.sales.dto.SalesFeedbackResponse;
import com.fruit.shop.sales.dto.StrategySummary;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
import com.fruit.shop.support.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售回流与效果看板（docs/01-技术方案.md 第 5 节接口 11、12）。
 *
 * <p>回流规则：
 * <ul>
 *   <li>按 batch_id 扣减 {@code fruit_batch.remain_qty}；扣到 0 时状态置 {@code SOLD_OUT}（退货回补后回到 ON_SALE）；</li>
 *   <li>数量超过现存量时按现存量扣减（避免负库存），并在响应里用 {@code overSell=true} 明确告知，不静默改数；</li>
 *   <li>扣减前对批次加行锁（{@code SELECT ... FOR UPDATE}），并发回流排队执行，不会互相覆盖库存；</li>
 *   <li>可选业务流水号 {@code requestNo} 做幂等：同一流水号只记一次，重复提交直接返回首次那笔（{@code duplicate=true}）；
 *       并发重复提交由批次行锁排队后再查一次，兜底靠 {@code sales_record.request_no} 唯一索引（撞索引整笔回滚后返回首笔）；</li>
 *   <li>每笔回流都写 {@code sales_record}，并按"该批次最近一条定价建议"的策略累加 {@code strategy_daily_stat}，
 *       让效果看板能直接对比 FIXED/RULE/MODEL。</li>
 * </ul>
 *
 * <p>本类没有任何定时/自动行为：不主动改批次状态、不主动生成统计数据（docs/05 NFR-03）。
 */
@Service
public class SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesService.class);

    private static final String STATUS_ON_SALE = "ON_SALE";

    private static final String STATUS_SOLD_OUT = "SOLD_OUT";

    private static final String STATUS_SCRAPPED = "SCRAPPED";

    private final SalesRecordMapper salesMapper;
    private final StrategyDailyStatMapper statMapper;
    private final FruitBatchMapper batchMapper;
    private final FruitItemMapper itemMapper;
    private final MemberMapper memberMapper;
    private final BatchService batchService;
    private final ItemService itemService;
    private final StrategyStatService statService;
    private final PricingService pricingService;
    private final PushEffectService pushEffectService;
    private final TransactionTemplate tx;

    public SalesService(SalesRecordMapper salesMapper,
                        StrategyDailyStatMapper statMapper,
                        FruitBatchMapper batchMapper,
                        FruitItemMapper itemMapper,
                        MemberMapper memberMapper,
                        BatchService batchService,
                        ItemService itemService,
                        StrategyStatService statService,
                        PricingService pricingService,
                        PushEffectService pushEffectService,
                        PlatformTransactionManager transactionManager) {
        this.salesMapper = salesMapper;
        this.statMapper = statMapper;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.memberMapper = memberMapper;
        this.batchService = batchService;
        this.itemService = itemService;
        this.statService = statService;
        this.pricingService = pricingService;
        this.pushEffectService = pushEffectService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 12. 销售回流

    /**
     * 销售回流入口。事务用 {@link TransactionTemplate} 显式包一层（而不是 {@code @Transactional}），
     * 是为了在"同一流水号并发提交、后到的一笔撞唯一索引"时：先让那笔整体回滚（库存扣减一起撤销），
     * 再在新事务里查出首笔记录返回——这一步必须在原事务之外做。
     */
    public SalesFeedbackResponse feedback(SalesFeedbackRequest request) {
        String requestNo = normalizeRequestNo(request.getRequestNo());
        try {
            return tx.execute(status -> doFeedback(request, requestNo));
        } catch (DuplicateKeyException race) {
            if (requestNo == null) {
                throw race;
            }
            log.info("[sales] 流水号 {} 并发重复提交，撞唯一索引后已回滚本笔，返回首笔记录", requestNo);
            return tx.execute(status -> {
                SalesRecord existing = findByRequestNo(requestNo);
                if (existing == null) {
                    throw race;
                }
                return duplicateResponse(existing, request, requestNo);
            });
        }
    }

    private SalesFeedbackResponse doFeedback(SalesFeedbackRequest request, String requestNo) {
        LocalDate bizDate = request.getBizDate();
        BigDecimal quantity = request.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0) {
            throw BusinessException.badRequest("quantity 不能为 0");
        }
        boolean isReturn = Boolean.TRUE.equals(request.getIsReturn()) || quantity.compareTo(BigDecimal.ZERO) < 0;
        BigDecimal requested = quantity.abs();
        BigDecimal unitPrice = PricingPolicy.round2(request.getUnitPrice());

        // v2：可选的成交会员（归因用）。传了就要求真实存在，避免脏 id 把归因链路污染成"永远匹配不上"
        Long memberId = request.getMemberId();
        if (memberId != null) {
            Member member = memberMapper.selectById(memberId);
            if (member == null) {
                throw BusinessException.badRequest("会员不存在：memberId=" + memberId);
            }
        }

        Long itemId = request.getItemId();
        FruitBatch batch = null;
        boolean autoPicked = false;
        if (request.getBatchId() != null) {
            batchService.requireBatch(request.getBatchId());
            // 行锁：同一批次的并发回流在这里排队，下面的读—改—写才安全
            batch = batchMapper.selectByIdForUpdate(request.getBatchId());
            if (itemId == null) {
                itemId = batch.getItemId();
            } else if (!itemId.equals(batch.getItemId())) {
                throw BusinessException.badRequest("itemId 与批次不匹配：批次 " + batch.getBatchNo()
                        + " 属于 itemId=" + batch.getItemId());
            }
        } else {
            if (itemId == null) {
                throw BusinessException.badRequest("itemId 与 batchId 至少传一个");
            }
            itemService.requireItem(itemId);
            batch = lockFifoBatch(itemId);
            autoPicked = batch != null;
        }
        FruitItem item = itemId == null ? null : itemMapper.selectById(itemId);

        // 幂等：放在批次加锁之后查，同一流水号的并发请求会在上面的行锁处排队，后到的这里就能看到首笔
        if (requestNo != null) {
            SalesRecord existing = findByRequestNo(requestNo);
            if (existing != null) {
                return duplicateResponse(existing, request, requestNo);
            }
        }

        BigDecimal soldQty = requested;
        BigDecimal remainQty = null;
        String batchStatus = null;
        boolean overSell = false;
        if (batch != null) {
            BigDecimal before = nz(batch.getRemainQty());
            if (isReturn) {
                remainQty = before.add(requested);
            } else {
                soldQty = requested.min(before);
                overSell = requested.compareTo(before) > 0;
                if (soldQty.compareTo(BigDecimal.ZERO) < 0) {
                    soldQty = BigDecimal.ZERO;
                }
                remainQty = before.subtract(soldQty);
            }
            batchStatus = remainQty.compareTo(BigDecimal.ZERO) <= 0 ? STATUS_SOLD_OUT : STATUS_ON_SALE;
            if (STATUS_SCRAPPED.equals(batch.getStatus())) {
                // 已报废批次不因退货"复活"：状态保持 SCRAPPED，只回补数量并在 message 里说明
                batchStatus = STATUS_SCRAPPED;
            } else {
                batch.setStatus(batchStatus);
            }
            batch.setRemainQty(remainQty);
            batch.setUpdatedAt(LocalDateTime.now());
            batchMapper.updateById(batch);
        }

        boolean isDiscount = request.getIsDiscount() != null
                ? request.getIsDiscount()
                : (item != null && item.getBasePrice() != null && unitPrice.compareTo(item.getBasePrice()) < 0);
        BigDecimal signedQty = isReturn ? soldQty.negate() : soldQty;

        Long salesRecordId = null;
        String strategy = statService.inferStrategy(batch == null ? null : batch.getId(), itemId, bizDate);
        boolean statUpdated = false;
        PushEffectService.AttributionResult attribution = null;
        if (isReturn || soldQty.compareTo(BigDecimal.ZERO) > 0) {
            SalesRecord record = new SalesRecord();
            record.setBizDate(bizDate);
            record.setSaleTime(request.getSaleTime() == null ? LocalDateTime.now() : request.getSaleTime());
            record.setItemId(itemId);
            record.setBatchId(batch == null ? null : batch.getId());
            record.setQuantity(signedQty);
            record.setUnitPrice(unitPrice);
            record.setIsDiscount(isDiscount);
            record.setIsReturn(isReturn);
            record.setMemberId(memberId);
            record.setSource(request.getSource() == null || request.getSource().isBlank()
                    ? "REAL" : request.getSource().trim().toUpperCase());
            record.setRequestNo(requestNo);
            salesMapper.insert(record);
            salesRecordId = record.getId();

            BigDecimal revenue = round2(unitPrice.multiply(signedQty));
            BigDecimal cost = batch == null || batch.getCostPrice() == null
                    ? BigDecimal.ZERO
                    : round2(batch.getCostPrice().multiply(signedQty));
            statUpdated = statService.accumulate(bizDate, strategy, signedQty, revenue, cost);

            // v2：写入后立刻触发一次归因匹配（3 小时窗口 + 同一单品），幂等可重复调用
            if (!isReturn) {
                attribution = pushEffectService.attributeSale(record);
            }
        }

        String message;
        if (batch == null) {
            message = "该商品没有可回流的在售批次，仅记录销售流水，未扣减库存";
        } else if (isReturn) {
            message = "退货已回补 " + PricingPolicy.qty(soldQty) + "，批次剩余 " + PricingPolicy.qty(remainQty)
                    + "，状态 " + batchStatus;
        } else if (soldQty.compareTo(BigDecimal.ZERO) == 0) {
            message = "批次 " + batch.getBatchNo() + " 已售罄，本次未产生扣减（overSell=true）";
        } else {
            message = "已回流 " + PricingPolicy.qty(soldQty) + "，批次剩余 " + PricingPolicy.qty(remainQty)
                    + "，状态 " + batchStatus
                    + (overSell ? "；请求数量超过现存量，已按现存量回流" : "");
        }
        log.info("[sales] 回流 bizDate={} itemId={} batchNo={} soldQty={} remainQty={} status={} strategy={} statUpdated={} memberId={} 归因={}",
                bizDate, itemId, batch == null ? null : batch.getBatchNo(), soldQty, remainQty, batchStatus,
                strategy, statUpdated, memberId, attribution == null ? "不适用" : attribution.note());

        return new SalesFeedbackResponse(remainQty, batchStatus, salesRecordId, bizDate, itemId,
                batch == null ? null : batch.getId(), batch == null ? null : batch.getBatchNo(),
                requested, soldQty, overSell, autoPicked, isReturn, strategy, statUpdated, message,
                memberId,
                attribution == null ? null : attribution.logId(),
                attribution != null && attribution.newlyAttributed(),
                PushEffectService.CONVERT_WINDOW_HOURS,
                attribution == null
                        ? (memberId == null ? "本笔没有 memberId，不参与推送转化归因" : "退货不参与推送转化归因")
                        : attribution.note(),
                requestNo, false);
    }

    // ------------------------------------------------------------------ 幂等（业务流水号）

    private static String normalizeRequestNo(String requestNo) {
        if (requestNo == null || requestNo.isBlank()) {
            return null;
        }
        String trimmed = requestNo.trim();
        if (trimmed.length() > 64) {
            throw BusinessException.badRequest("requestNo 最长 64 个字符");
        }
        return trimmed;
    }

    private SalesRecord findByRequestNo(String requestNo) {
        return salesMapper.selectOne(Wrappers.<SalesRecord>lambdaQuery()
                .eq(SalesRecord::getRequestNo, requestNo)
                .last("LIMIT 1"));
    }

    /**
     * 重复提交：不扣库存、不写流水、不写统计，返回首笔的记录与批次当前状态。
     * 同一流水号却换了商品/批次/数量/单价/退货方向，说明调用方把流水号用错了，拒绝（409）而不是悄悄返回旧结果。
     */
    private SalesFeedbackResponse duplicateResponse(SalesRecord existing, SalesFeedbackRequest request, String requestNo) {
        BigDecimal requestedSigned = Boolean.TRUE.equals(request.getIsReturn()) && request.getQuantity().signum() > 0
                ? request.getQuantity().negate() : request.getQuantity();
        boolean sameItem = request.getItemId() == null || request.getItemId().equals(existing.getItemId());
        boolean sameBatch = request.getBatchId() == null || request.getBatchId().equals(existing.getBatchId());
        boolean sameUnitPrice = PricingPolicy.round2(request.getUnitPrice())
                .compareTo(PricingPolicy.round2(existing.getUnitPrice())) == 0;
        // 首笔可能因库存不足被截断（overSell），所以数量只要求方向一致、且请求量不小于实记量
        boolean sameDirection = requestedSigned.signum() == existing.getQuantity().signum();
        boolean qtyCovers = requestedSigned.abs().compareTo(existing.getQuantity().abs()) >= 0;
        if (!(sameItem && sameBatch && sameUnitPrice && sameDirection && qtyCovers)) {
            throw BusinessException.conflict("业务流水号 " + requestNo + " 已用于另一笔销售（销售流水 #" + existing.getId()
                    + "：itemId=" + existing.getItemId() + "，batchId=" + existing.getBatchId()
                    + "，数量 " + PricingPolicy.qty(existing.getQuantity()) + "，单价 " + PricingPolicy.money(existing.getUnitPrice())
                    + "），请为新的一笔生成新的流水号");
        }
        FruitBatch batch = existing.getBatchId() == null ? null : batchMapper.selectById(existing.getBatchId());
        boolean isReturn = Boolean.TRUE.equals(existing.getIsReturn());
        String strategy = statService.inferStrategy(existing.getBatchId(), existing.getItemId(), existing.getBizDate());
        String message = "重复提交：流水号 " + requestNo + " 已在 " + existing.getSaleTime() + " 记为销售流水 #" + existing.getId()
                + "，本次没有重复扣减库存、没有重复写流水和统计"
                + (batch == null ? "" : "；批次当前剩余 " + PricingPolicy.qty(batch.getRemainQty()));
        log.info("[sales] 重复提交 requestNo={} salesRecordId={}，已忽略", requestNo, existing.getId());
        return new SalesFeedbackResponse(
                batch == null ? null : batch.getRemainQty(),
                batch == null ? null : batch.getStatus(),
                existing.getId(), existing.getBizDate(), existing.getItemId(),
                existing.getBatchId(), batch == null ? null : batch.getBatchNo(),
                request.getQuantity().abs(), existing.getQuantity().abs(), false, false, isReturn,
                strategy, false, message, existing.getMemberId(),
                null, false, PushEffectService.CONVERT_WINDOW_HOURS,
                "重复提交不再触发归因（首笔写入时已经匹配过）",
                requestNo, true);
    }

    // ------------------------------------------------------------------ 11. 效果看板

    /** 效果看板：优先按 strategy 聚合 strategy_daily_stat；该表为空时回退 sales_record 实时汇总并如实标注。 */
    public EffectResponse effect(LocalDate from, LocalDate to, String strategy) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) {
            throw BusinessException.badRequest("from 不能晚于 to（from=" + start + ", to=" + end + "）");
        }
        String active = pricingService.activeStrategy();
        boolean all = strategy == null || strategy.isBlank() || "ALL".equalsIgnoreCase(strategy.trim());
        String code = all ? null : pricingService.normalizeStrategy(strategy);
        String echo = code == null ? "ALL" : code;

        List<StrategyDailyStat> stats = statMapper.selectList(Wrappers.<StrategyDailyStat>lambdaQuery()
                .ge(StrategyDailyStat::getBizDate, start)
                .le(StrategyDailyStat::getBizDate, end)
                .eq(code != null, StrategyDailyStat::getStrategy, code)
                .orderByAsc(StrategyDailyStat::getBizDate)
                .orderByAsc(StrategyDailyStat::getStrategy));
        if (!stats.isEmpty()) {
            return fromStatRows(start, end, echo, active, stats);
        }
        return fromSalesRecords(start, end, echo, active, code);
    }

    private EffectResponse fromStatRows(LocalDate start, LocalDate end, String echo, String active,
                                        List<StrategyDailyStat> rows) {
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal scrap = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal stockoutSum = BigDecimal.ZERO;
        int stockoutCount = 0;
        List<EffectDailyRow> daily = new ArrayList<>(rows.size());
        Map<String, List<StrategyDailyStat>> grouped = new LinkedHashMap<>();

        for (StrategyDailyStat row : rows) {
            BigDecimal rowSold = nz(row.getSoldQty());
            BigDecimal rowScrap = nz(row.getScrapQty());
            BigDecimal rowRevenue = nz(row.getRevenue());
            BigDecimal rowCost = nz(row.getCost());
            BigDecimal rowProfit = row.getGrossProfit() != null ? row.getGrossProfit() : rowRevenue.subtract(rowCost);
            BigDecimal rowScrapRate = row.getScrapRate() != null
                    ? row.getScrapRate() : StrategyStatService.scrapRate(rowScrap, rowSold);
            BigDecimal rowStockout = nz(row.getStockoutRate());

            sold = sold.add(rowSold);
            scrap = scrap.add(rowScrap);
            revenue = revenue.add(rowRevenue);
            cost = cost.add(rowCost);
            if (row.getStockoutRate() != null) {
                stockoutSum = stockoutSum.add(rowStockout);
                stockoutCount++;
            }
            daily.add(new EffectDailyRow(row.getBizDate(), row.getStrategy(), scale4(rowScrapRate), scale2(rowProfit),
                    scale2(rowRevenue), scale2(rowSold), scale2(rowScrap), scale2(rowCost), scale4(rowStockout)));
            grouped.computeIfAbsent(row.getStrategy(), key -> new ArrayList<>()).add(row);
        }

        BigDecimal grossProfit = scale2(revenue.subtract(cost));
        BigDecimal scrapRate = StrategyStatService.scrapRate(scrap, sold);
        BigDecimal stockoutRate = stockoutCount == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : stockoutSum.divide(BigDecimal.valueOf(stockoutCount), 4, RoundingMode.HALF_UP);

        List<StrategySummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<StrategyDailyStat>> entry : grouped.entrySet()) {
            BigDecimal gSold = BigDecimal.ZERO;
            BigDecimal gScrap = BigDecimal.ZERO;
            BigDecimal gRevenue = BigDecimal.ZERO;
            BigDecimal gCost = BigDecimal.ZERO;
            for (StrategyDailyStat row : entry.getValue()) {
                gSold = gSold.add(nz(row.getSoldQty()));
                gScrap = gScrap.add(nz(row.getScrapQty()));
                gRevenue = gRevenue.add(nz(row.getRevenue()));
                gCost = gCost.add(nz(row.getCost()));
            }
            summaries.add(new StrategySummary(entry.getKey(), scale2(gSold), scale2(gScrap), scale2(gRevenue),
                    scale2(gCost), scale2(gRevenue.subtract(gCost)), StrategyStatService.scrapRate(gScrap, gSold),
                    stockoutRate, entry.getValue().size()));
        }

        String note = "数据来源：strategy_daily_stat（按 strategy 聚合，日统计唯一索引保证不重不漏）。"
                + "损耗率 = 报废量 /（销量 + 报废量）。";
        return new EffectResponse(scale4(scrapRate), grossProfit, scale2(revenue), scale2(cost), scale2(sold),
                scale2(scrap), stockoutRate, daily, start, end, echo, active, "strategy_daily_stat", true, note, summaries);
    }

    private EffectResponse fromSalesRecords(LocalDate start, LocalDate end, String echo, String active, String code) {
        List<SalesRecord> sales = salesMapper.selectList(Wrappers.<SalesRecord>lambdaQuery()
                .ge(SalesRecord::getBizDate, start)
                .le(SalesRecord::getBizDate, end)
                .orderByAsc(SalesRecord::getBizDate)
                .orderByAsc(SalesRecord::getId));

        String emptyNote = "区间内既没有策略日统计（strategy_daily_stat），也没有销售回流记录（sales_record）；"
                + "系统保持空闲，不会自动生成任何数据。跑完定价与仿真脚本、或有真实回流后这里会自动出数。";
        if (sales.isEmpty()) {
            return new EffectResponse(zero2(), zero2(), zero2(), zero2(), zero2(), zero2(), zero4(),
                    List.of(), start, end, echo, active, "sales_record", false, emptyNote, List.of());
        }

        List<Long> batchIds = sales.stream().map(SalesRecord::getBatchId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<Long, FruitBatch> batches = new HashMap<>();
        if (!batchIds.isEmpty()) {
            batchMapper.selectBatchIds(batchIds).forEach(batch -> batches.put(batch.getId(), batch));
        }

        Map<LocalDate, BigDecimal[]> perDay = new LinkedHashMap<>();
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        boolean costComplete = true;
        for (SalesRecord sale : sales) {
            BigDecimal qty = nz(sale.getQuantity());
            BigDecimal revenueOfSale = nz(sale.getUnitPrice()).multiply(qty);
            FruitBatch batch = sale.getBatchId() == null ? null : batches.get(sale.getBatchId());
            BigDecimal costOfSale;
            if (batch == null || batch.getCostPrice() == null) {
                costOfSale = BigDecimal.ZERO;
                costComplete = false;
            } else {
                costOfSale = batch.getCostPrice().multiply(qty);
            }
            BigDecimal[] bucket = perDay.computeIfAbsent(sale.getBizDate(),
                    key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            bucket[0] = bucket[0].add(qty);
            bucket[1] = bucket[1].add(revenueOfSale);
            bucket[2] = bucket[2].add(costOfSale);
            sold = sold.add(qty);
            revenue = revenue.add(revenueOfSale);
            cost = cost.add(costOfSale);
        }

        List<EffectDailyRow> daily = new ArrayList<>(perDay.size());
        for (Map.Entry<LocalDate, BigDecimal[]> entry : perDay.entrySet()) {
            BigDecimal daySold = entry.getValue()[0];
            BigDecimal dayRevenue = entry.getValue()[1];
            BigDecimal dayCost = entry.getValue()[2];
            daily.add(new EffectDailyRow(entry.getKey(), "REAL", zero4(), scale2(dayRevenue.subtract(dayCost)),
                    scale2(dayRevenue), scale2(daySold), zero2(), scale2(dayCost), zero4()));
        }

        StringBuilder note = new StringBuilder();
        note.append("strategy_daily_stat 在区间内没有数据，已回退为 sales_record 实时汇总：")
                .append("不含策略归属（策略维度需要定价建议 + 效果统计链路），也不含报废量，因此损耗率显示为 0；")
                .append("跑完仿真/评测脚本后会自动切回策略维度。");
        if (code != null) {
            note.append("注意：当前请求带了 strategy=").append(code).append(" 过滤，但回退口径无法做策略归属，返回的是全部流水。");
        }
        if (!costComplete) {
            note.append("部分流水没有关联批次或批次成本价，成本按 0 计入（costComplete=false）。");
        }

        return new EffectResponse(zero4(), scale2(revenue.subtract(cost)), scale2(revenue), scale2(cost),
                scale2(sold), zero2(), zero4(), daily, start, end, echo, active, "sales_record", costComplete,
                note.toString(), List.of());
    }

    /** FIFO：该商品最早到期的在售批次。 */
    /**
     * 先进先出选批次并加行锁。选中与加锁之间该批次可能被并发请求卖空，
     * 所以锁到之后再核对一次；不再在售就换下一个批次（有限次重试，批次数有限）。
     */
    private FruitBatch lockFifoBatch(Long itemId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            FruitBatch candidate = fifoBatch(itemId);
            if (candidate == null) {
                return null;
            }
            FruitBatch locked = batchMapper.selectByIdForUpdate(candidate.getId());
            if (locked != null && STATUS_ON_SALE.equals(locked.getStatus())
                    && nz(locked.getRemainQty()).compareTo(BigDecimal.ZERO) > 0) {
                return locked;
            }
        }
        return null;
    }

    private FruitBatch fifoBatch(Long itemId) {
        return batchMapper.selectOne(Wrappers.<FruitBatch>lambdaQuery()
                .eq(FruitBatch::getItemId, itemId)
                .eq(FruitBatch::getStatus, STATUS_ON_SALE)
                .gt(FruitBatch::getRemainQty, BigDecimal.ZERO)
                .orderByAsc(FruitBatch::getExpireDate)
                .last("LIMIT 1"));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale2(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal value) {
        return nz(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero2() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero4() {
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
}
