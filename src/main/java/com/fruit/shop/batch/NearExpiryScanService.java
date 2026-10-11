package com.fruit.shop.batch;

import com.fruit.shop.batch.domain.BatchMetrics;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.dto.ScanResult;
import com.fruit.shop.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读临期扫描：算派生指标、刷新"有风险"清单、写自己的日志（docs/07 第 2 节）。
 *
 * <p><b>允许</b>：扫描临期批次、计算并写入 {@code fruit_batch} 的派生指标列（freshness /
 * estimated_sellable_days / planned_sale_qty / expected_scrap_qty / at_risk / metrics_updated_at）、
 * 写任务日志。
 * <br><b>禁止</b>：自动改 {@code remain_qty / status}、自动生成 {@code pricing_suggestion} 或
 * {@code pricing_decision}、自动发 {@code push_task / push_log}。所有业务状态变更必须由显式接口调用触发
 * ——这条边界由 {@code BatchScanJobTest} 与 {@code NoAutonomousActionTest} 双重锁定。
 */
@Service
public class NearExpiryScanService {

    private static final Logger log = LoggerFactory.getLogger(NearExpiryScanService.class);

    private final BatchService batchService;
    private final BatchMetricsService metricsService;
    private final AppProperties props;

    public NearExpiryScanService(BatchService batchService, BatchMetricsService metricsService, AppProperties props) {
        this.batchService = batchService;
        this.metricsService = metricsService;
        this.props = props;
    }

    /**
     * 跑一轮扫描（可被定时任务与单测直接调用）。
     *
     * @param bizDate 业务日，null 取今天
     */
    @Transactional
    public ScanResult scanOnce(LocalDate bizDate) {
        LocalDate biz = bizDate == null ? LocalDate.now() : bizDate;
        int threshold = props.nearExpiryDays();
        List<FruitBatch> batches = batchService.nearExpiryBatches(biz, threshold);
        LocalDateTime computedAt = LocalDateTime.now().withNano(0);

        // 整个业务日只查一次销量窗口与客流窗口，再逐个批次算指标。
        BatchMetricsService.SalesWindow window = metricsService.loadWindow(biz);
        int updated = 0;
        int atRisk = 0;
        List<Long> riskyIds = new ArrayList<>();
        List<String> riskyNos = new ArrayList<>();
        for (FruitBatch batch : batches) {
            BatchMetrics metrics = metricsService.compute(biz, batch, window, computedAt);
            metricsService.saveMetrics(batch, metrics);
            updated++;
            if (metrics.atRisk()) {
                atRisk++;
                if (riskyIds.size() < ScanResult.MAX_REPORTED) {
                    riskyIds.add(batch.getId());
                    riskyNos.add(batch.getBatchNo());
                }
            }
        }
        String message = "扫描 " + biz + " 的临期批次（days_left ≤ " + threshold + "）：命中 " + batches.size()
                + " 个，写入派生指标 " + updated + " 条，有风险 " + atRisk
                + " 个；只写派生字段，未生成任何决策/推送。";
        log.info("[scan] {}", message);
        if (!riskyNos.isEmpty()) {
            log.info("[scan] 有风险清单（前 {} 个）：{}", riskyNos.size(), String.join("、", riskyNos));
        }
        return new ScanResult(biz, threshold, batches.size(), updated, atRisk,
                List.copyOf(riskyIds), List.copyOf(riskyNos), computedAt, message);
    }
}
