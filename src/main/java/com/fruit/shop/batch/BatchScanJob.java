package com.fruit.shop.batch;

import com.fruit.shop.batch.dto.ScanResult;
import com.fruit.shop.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 小时级"临期扫描"定时入口（docs/07 第 2 节）。
 *
 * <p>只做扫描 + 写派生指标 + 刷新有风险清单 + 写日志，不产生任何业务动作：
 * 不生成定价建议、不写决策、不发推送。因此它与 {@code docs/05} 的"无自动执行"承诺不冲突
 * ——"自动改价/自动发消息"依然被结构上禁止。
 *
 * <p>两个开关都在 {@code AppProperties} 里带默认值（yml 未配置也能跑）：
 * <ul>
 *   <li>{@code app.scan.enabled}（默认 true）：关闭后定时任务直接返回，不碰任何数据；</li>
 *   <li>{@code app.scan.cron}（默认 {@code 0 0 * * * *}，每小时整点）。</li>
 * </ul>
 *
 * <p>扫描逻辑在 {@link NearExpiryScanService#scanOnce(LocalDate)}，单测直接调用它或
 * {@link #scanOnce(LocalDate)}（后者额外受开关约束），不需要等定时器触发。
 */
@Component
public class BatchScanJob {

    private static final Logger log = LoggerFactory.getLogger(BatchScanJob.class);

    private final NearExpiryScanService scanService;
    private final AppProperties props;

    public BatchScanJob(NearExpiryScanService scanService, AppProperties props) {
        this.scanService = scanService;
        this.props = props;
    }

    /** 定时入口：每小时跑一轮（cron 可被 {@code app.scan.cron} 覆盖）。 */
    @Scheduled(cron = "${app.scan.cron:0 0 * * * *}")
    public void hourlyScan() {
        ScanResult result = scanOnce(LocalDate.now());
        if (result != null) {
            log.info("[scan] 定时扫描完成：{}", result.message());
        }
    }

    /**
     * 跑一轮扫描；{@code app.scan.enabled=false} 时返回 {@code null} 且不写任何数据。
     *
     * <p>这是给测试与运维直接调用的入口（名字与语义同 {@link NearExpiryScanService#scanOnce(LocalDate)}）。
     */
    public ScanResult scanOnce(LocalDate bizDate) {
        if (!props.getScan().isEnabled()) {
            log.info("[scan] app.scan.enabled=false，本轮扫描已跳过（不写任何数据）");
            return null;
        }
        try {
            return scanService.scanOnce(bizDate);
        } catch (RuntimeException e) {
            // 定时任务失败不能影响主流程：记录后等下个周期重试。
            log.error("[scan] 只读扫描失败，本轮跳过（不影响任何业务数据）", e);
            return null;
        }
    }
}
