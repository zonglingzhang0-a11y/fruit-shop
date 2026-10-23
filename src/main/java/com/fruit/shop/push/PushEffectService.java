package com.fruit.shop.push;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.push.domain.PushLog;
import com.fruit.shop.push.domain.PushTask;
import com.fruit.shop.push.dto.PushAttributionRequest;
import com.fruit.shop.push.dto.PushAttributionResponse;
import com.fruit.shop.push.dto.PushLogRow;
import com.fruit.shop.push.dto.PushOpenRequest;
import com.fruit.shop.push.dto.PushOpenResponse;
import com.fruit.shop.push.dto.PushStatsResponse;
import com.fruit.shop.push.dto.PushTaskStat;
import com.fruit.shop.push.mapper.PushLogMapper;
import com.fruit.shop.push.mapper.PushTaskMapper;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.support.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 推送效果：<b>打开标记 + 3 小时同单品转化归因 + 打开率/转化率统计</b>
 * （docs/07-与参考架构的差异与补齐.md 第 1 节 C、第 3 节验收 4）。
 *
 * <p>归因口径（冻结，<b>不做跨品类、不追踪长期行为</b>）：
 * <ol>
 *   <li>只有 {@code push_log.member_id} 非空、且真正送达（{@code skip_reason} 为空）的行才可能被归因；</li>
 *   <li>该行发出后 {@value #CONVERT_WINDOW_HOURS} 小时内，同一会员购买了<b>同一单品</b>
 *       （{@code push_log → push_task.related_item_id == sales_record.item_id}）的销售记录，才算转化；</li>
 *   <li>命中后写 {@code converted=true}、{@code converted_at=成交时刻}、{@code related_sale_id}、{@code convert_window_hours=3}；</li>
 *   <li>幂等：同一条销售记录只挂一条 log（先查 {@code related_sale_id} 是否已存在），
 *       同一条 log 也只归因一次（候选只取 {@code converted=false}，并用 CAS 更新兜住并发）；
 *       同一会员同窗口有多条推送时，归因到<b>最近一条</b>未转化的送达推送。</li>
 * </ol>
 *
 * <p>{@value #CONVERT_WINDOW_HOURS} 目前是写死的常量（契约冻结口径）；若将来要店主可调，
 * 提到配置项 {@code app.push.convert-window-hours} 即可，其它代码不用改——
 * 因为每条 log 都已经把自己的窗口写进 {@code convert_window_hours} 列，统计口径自洽。
 *
 * <p>统计口径：打开率 = 打开数 ÷ 送达数，转化率 = 转化数 ÷ 送达数；
 * 送达数 = {@code member_id 非空且 skip_reason 为空} 的行数，跳过行（限频/静默/退订）与群发文案行都不进分母。
 */
@Service
public class PushEffectService {

    /** 归因窗口（小时）：推送发出后多久内的同单品成交算转化。契约冻结，勿随意改。 */
    public static final int CONVERT_WINDOW_HOURS = 3;

    /** 后台批量归因默认回看的自然日数。 */
    public static final int DEFAULT_BACKFILL_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(PushEffectService.class);

    private final PushTaskMapper taskMapper;
    private final PushLogMapper logMapper;
    private final SalesRecordMapper salesMapper;
    private final MemberMapper memberMapper;
    private final FruitItemMapper itemMapper;

    public PushEffectService(PushTaskMapper taskMapper,
                             PushLogMapper logMapper,
                             SalesRecordMapper salesMapper,
                             MemberMapper memberMapper,
                             FruitItemMapper itemMapper) {
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.salesMapper = salesMapper;
        this.memberMapper = memberMapper;
        this.itemMapper = itemMapper;
    }

    // ------------------------------------------------------------------ 归因

    /**
     * 为一条销售记录匹配推送转化（可重复调用、幂等）。
     *
     * <p>由 {@code POST /api/sales/feedback} 在写入销售记录后自动触发；也可由
     * {@code POST /api/push/attribute} 手工重跑。
     */
    /** 该笔销售已归因到的推送日志（没有则 null）；重复提交回放首笔结果时用。 */
    public PushLog attributedLogOf(Long salesRecordId) {
        if (salesRecordId == null) {
            return null;
        }
        return logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getRelatedSaleId, salesRecordId)
                .last("LIMIT 1"));
    }

    @Transactional
    public AttributionResult attributeSale(SalesRecord sale) {
        if (sale == null || sale.getId() == null) {
            return AttributionResult.none(null, "销售记录不存在，跳过归因");
        }
        if (sale.getMemberId() == null) {
            return AttributionResult.none(null, "该销售记录没有 member_id（未识别会员），不参与归因");
        }
        if (Boolean.TRUE.equals(sale.getIsReturn())) {
            return AttributionResult.none(null, "退货记录不计转化");
        }
        if (sale.getItemId() == null || sale.getSaleTime() == null) {
            return AttributionResult.none(null, "缺少 item_id / sale_time，无法归因");
        }

        // 幂等 1：这条销售已经挂到某条 log 上 → 不重复挂
        PushLog already = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getRelatedSaleId, sale.getId())
                .last("LIMIT 1"));
        if (already != null) {
            return AttributionResult.already(already, "该销售记录已归因到 push_log #" + already.getId() + "（幂等，不重复挂）");
        }

        // 候选：同会员、已送达、未转化、发出时间落在 [成交时刻 - 3 小时, 成交时刻]，最近的优先
        List<PushLog> candidates = logMapper.selectList(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getMemberId, sale.getMemberId())
                .isNull(PushLog::getSkipReason)
                .eq(PushLog::getConverted, false)
                .le(PushLog::getSentAt, sale.getSaleTime())
                .ge(PushLog::getSentAt, sale.getSaleTime().minusHours(CONVERT_WINDOW_HOURS))
                .orderByDesc(PushLog::getSentAt)
                .orderByDesc(PushLog::getId));
        if (candidates.isEmpty()) {
            return AttributionResult.none(null, "窗口（" + CONVERT_WINDOW_HOURS + " 小时）内该会员没有未转化的送达推送");
        }

        Map<Long, PushTask> tasks = tasksOf(candidates.stream().map(PushLog::getTaskId).toList());
        PushLog hit = null;
        for (PushLog candidate : candidates) {
            PushTask task = tasks.get(candidate.getTaskId());
            if (task != null && sale.getItemId().equals(task.getRelatedItemId())) {
                hit = candidate;
                break;
            }
        }
        if (hit == null) {
            return AttributionResult.none(null, "窗口内有推送，但不是同一单品（不跨单品/品类归因）");
        }

        // 幂等 2：CAS 更新（converted=false 才写），并发下同一条 log 不会归因两次
        PushLog patch = new PushLog();
        patch.setConverted(true);
        patch.setConvertedAt(sale.getSaleTime());
        patch.setRelatedSaleId(sale.getId());
        patch.setConvertWindowHours(CONVERT_WINDOW_HOURS);
        int rows = logMapper.update(patch, Wrappers.<PushLog>lambdaUpdate()
                .eq(PushLog::getId, hit.getId())
                .eq(PushLog::getConverted, false));
        if (rows == 0) {
            PushLog again = logMapper.selectById(hit.getId());
            if (again != null && sale.getId().equals(again.getRelatedSaleId())) {
                return AttributionResult.already(again, "并发下已由另一次调用完成归因（幂等）");
            }
            return AttributionResult.none(null, "该推送已被其它销售记录归因，本次不重复挂");
        }
        hit.setConverted(true);
        hit.setConvertedAt(sale.getSaleTime());
        hit.setRelatedSaleId(sale.getId());
        hit.setConvertWindowHours(CONVERT_WINDOW_HOURS);
        log.info("[push] 归因成功 push_log={} taskId={} memberId={} itemId={} saleId={} 窗口={}h 成交时刻={}",
                hit.getId(), hit.getTaskId(), hit.getMemberId(), sale.getItemId(), sale.getId(),
                CONVERT_WINDOW_HOURS, sale.getSaleTime());
        return AttributionResult.newly(hit, "归因成功：push_log #" + hit.getId()
                + " 在 " + CONVERT_WINDOW_HOURS + " 小时窗口内命中同单品成交（saleId=" + sale.getId() + "）");
    }

    /** 批量/手工重跑归因（幂等）：saleId → 单条；bizDate → 单日；from/to → 区间（默认最近 7 天）。 */
    @Transactional
    public PushAttributionResponse attribute(PushAttributionRequest request) {
        PushAttributionRequest req = request == null ? new PushAttributionRequest() : request;
        LocalDate end;
        LocalDate start;
        List<SalesRecord> sales;

        if (req.getSaleId() != null) {
            SalesRecord sale = salesMapper.selectById(req.getSaleId());
            if (sale == null) {
                throw BusinessException.notFound("销售记录不存在：saleId=" + req.getSaleId());
            }
            sales = List.of(sale);
            start = sale.getBizDate();
            end = sale.getBizDate();
        } else {
            end = req.getTo() != null ? req.getTo() : (req.getBizDate() != null ? req.getBizDate() : LocalDate.now());
            start = req.getFrom() != null ? req.getFrom()
                    : (req.getBizDate() != null ? req.getBizDate() : end.minusDays(DEFAULT_BACKFILL_DAYS - 1L));
            if (start.isAfter(end)) {
                throw BusinessException.badRequest("from 不能晚于 to（from=" + start + ", to=" + end + "）");
            }
            sales = salesMapper.selectList(Wrappers.<SalesRecord>lambdaQuery()
                    .ge(SalesRecord::getBizDate, start)
                    .le(SalesRecord::getBizDate, end)
                    .isNotNull(SalesRecord::getMemberId)
                    .eq(SalesRecord::getIsReturn, false)
                    .eq(req.getMemberId() != null, SalesRecord::getMemberId, req.getMemberId())
                    .orderByAsc(SalesRecord::getId));
        }

        int scanned = 0;
        int newly = 0;
        int already = 0;
        int unmatched = 0;
        List<Long> convertedLogIds = new ArrayList<>();
        for (SalesRecord sale : sales) {
            if (sale.getMemberId() == null || Boolean.TRUE.equals(sale.getIsReturn())) {
                continue;
            }
            scanned++;
            AttributionResult result = attributeSale(sale);
            if (result.newlyAttributed()) {
                newly++;
                convertedLogIds.add(result.logId());
            } else if (result.alreadyAttributed()) {
                already++;
            } else {
                unmatched++;
            }
        }
        String note = "归因口径：送达的 push_log 发出后 " + CONVERT_WINDOW_HOURS
                + " 小时内，同会员购买同一单品的销售记录算转化；超窗/跨单品/退货不计；"
                + "同一条销售记录只挂一条 log，可重复调用且幂等（第二次 newlyAttributed=0）。";
        log.info("[push] 手工归因 from={} to={} memberId={} 扫描={} 新归因={} 已归因={} 未匹配={}",
                start, end, req.getMemberId(), scanned, newly, already, unmatched);
        return new PushAttributionResponse(start, end, req.getMemberId(), scanned, newly, already, unmatched,
                CONVERT_WINDOW_HOURS, convertedLogIds, note);
    }

    // ------------------------------------------------------------------ 打开

    /** 标记一条 push_log 已打开（幂等：重复标记保留首次 opened_at）。 */
    @Transactional
    public PushOpenResponse markOpened(PushOpenRequest request) {
        PushOpenRequest req = request == null ? new PushOpenRequest() : request;
        PushLog row = locate(req);
        if (row == null) {
            throw BusinessException.notFound("未找到对应的 push_log 行：请传 logId，或 taskId+memberId");
        }
        if (row.getSkipReason() != null) {
            throw BusinessException.badRequest("该行是被拦下的尝试记录（skip_reason=" + row.getSkipReason()
                    + "），没有送达，不能标记打开");
        }
        if (row.getMemberId() == null) {
            throw BusinessException.badRequest("该行是群发文案（member_id 为空），不针对个人，不能标记打开；"
                    + "打开率只统计个人送达行");
        }
        boolean alreadyOpened = Boolean.TRUE.equals(row.getOpened());
        LocalDateTime openedAt = req.getOpenedAt() == null ? LocalDateTime.now() : req.getOpenedAt();
        if (!alreadyOpened) {
            PushLog patch = new PushLog();
            patch.setId(row.getId());
            patch.setOpened(true);
            patch.setOpenedAt(openedAt);
            logMapper.updateById(patch);
            row.setOpened(true);
            row.setOpenedAt(openedAt);
        }
        String note = alreadyOpened
                ? "该行之前已标记打开（幂等，opened_at 仍是首次打开时间）"
                : "已标记打开；打开率 = 打开数 ÷ 送达数";
        log.info("[push] 标记打开 push_log={} taskId={} memberId={} alreadyOpened={}",
                row.getId(), row.getTaskId(), row.getMemberId(), alreadyOpened);
        return new PushOpenResponse(row.getId(), row.getTaskId(), row.getMemberId(), true, row.getOpenedAt(),
                alreadyOpened, Boolean.TRUE.equals(row.getConverted()), row.getRelatedSaleId(), note);
    }

    private PushLog locate(PushOpenRequest req) {
        if (req.getLogId() != null) {
            return logMapper.selectById(req.getLogId());
        }
        if (req.getTaskId() == null) {
            return null;
        }
        return logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, req.getTaskId())
                .eq(req.getMemberId() != null, PushLog::getMemberId, req.getMemberId())
                .isNull(req.getMemberId() == null, PushLog::getMemberId)
                .isNull(PushLog::getSkipReason)
                .orderByDesc(PushLog::getId)
                .last("LIMIT 1"));
    }

    // ------------------------------------------------------------------ 统计

    /**
     * 推送效果统计：整体打开率/转化率 + 按任务明细（接口 {@code GET /api/push/stats}）。
     *
     * @param withLogs true 时在按任务明细里带上每一行 push_log（用于单任务复盘）
     */
    public PushStatsResponse stats(LocalDate from, LocalDate to, Long taskId, boolean withLogs) {
        boolean byDate = dateFiltered(from, to, taskId);
        List<PushTaskStat> byTask = tasks(from, to, taskId, withLogs);
        int delivered = 0;
        int opened = 0;
        int converted = 0;
        int skippedLimit = 0;
        int skippedQuiet = 0;
        int skippedOptOut = 0;
        int groupRows = 0;
        int sentTasks = 0;
        int plannedTasks = 0;
        for (PushTaskStat row : byTask) {
            delivered += row.delivered();
            opened += row.opened();
            converted += row.converted();
            skippedLimit += row.skippedLimit();
            skippedQuiet += row.skippedQuiet();
            skippedOptOut += row.skippedOptOut();
            groupRows += row.groupRows();
            if (PushTask.STATUS_SENT.equals(row.status())) {
                sentTasks++;
            } else if (PushTask.STATUS_PLANNED.equals(row.status())) {
                plannedTasks++;
            }
        }
        LocalDate end = byDate ? (to == null ? LocalDate.now() : to) : null;
        LocalDate start = byDate ? (from == null ? end.minusDays(6) : from) : null;
        String note = "口径：送达数 = push_log 中 member_id 非空且 skip_reason 为空的行数（" + delivered + " 行）；"
                + "打开率 = 打开数 ÷ 送达数；转化率 = 推送后成交数 ÷ 送达数；"
                + "限频/静默/退订拦下的行（" + (skippedLimit + skippedQuiet + skippedOptOut)
                + " 行）与群发文案行（" + groupRows + " 行）都不计入分母；"
                + "转化窗口 = " + CONVERT_WINDOW_HOURS + " 小时且必须同一单品；"
                + (byDate ? "" : "本次按 taskId 精确查询，不限日期；")
                + "打开/转化都要显式调用接口（POST /api/push/open、POST /api/sales/feedback 或 POST /api/push/attribute），系统不会自动伪造。";
        return new PushStatsResponse(start, end, taskId, CONVERT_WINDOW_HOURS, byTask.size(), plannedTasks,
                sentTasks, delivered, opened, converted, rate(opened, delivered), rate(converted, delivered),
                skippedLimit, skippedQuiet, skippedOptOut, groupRows, byTask, note);
    }

    /**
     * 按任务列出计划与效果（接口 {@code GET /api/push/tasks} 与 stats 明细共用）。
     *
     * <p>日期口径：给了 {@code from/to}（或单日）就按 biz_date 过滤；只给 {@code taskId} 时<b>不限日期</b>
     * ——否则查一条历史计划会被默认的"最近 7 天"悄悄过滤掉，看起来像"计划不存在"。
     */
    public List<PushTaskStat> tasks(LocalDate from, LocalDate to, Long taskId, boolean withLogs) {
        boolean byDate = dateFiltered(from, to, taskId);
        LocalDate end = byDate ? (to == null ? LocalDate.now() : to) : null;
        LocalDate start = byDate ? (from == null ? end.minusDays(6) : from) : null;
        if (byDate && start.isAfter(end)) {
            throw BusinessException.badRequest("from 不能晚于 to（from=" + start + ", to=" + end + "）");
        }
        List<PushTask> tasks = taskMapper.selectList(Wrappers.<PushTask>lambdaQuery()
                .ge(byDate, PushTask::getBizDate, start)
                .le(byDate, PushTask::getBizDate, end)
                .eq(taskId != null, PushTask::getId, taskId)
                .orderByAsc(PushTask::getBizDate)
                .orderByAsc(PushTask::getId));
        Map<Long, String> itemNames = itemNames(tasks.stream().map(PushTask::getRelatedItemId).toList());

        List<PushTaskStat> rows = new ArrayList<>(tasks.size());
        for (PushTask task : tasks) {
            List<PushLog> logs = logMapper.selectList(Wrappers.<PushLog>lambdaQuery()
                    .eq(PushLog::getTaskId, task.getId())
                    .orderByAsc(PushLog::getId));
            int delivered = 0;
            int opened = 0;
            int converted = 0;
            int skippedLimit = 0;
            int skippedQuiet = 0;
            int skippedOptOut = 0;
            int groupRows = 0;
            for (PushLog row : logs) {
                if (row.getMemberId() == null) {
                    groupRows++;
                } else if (row.getSkipReason() == null) {
                    delivered++;
                    if (Boolean.TRUE.equals(row.getOpened())) {
                        opened++;
                    }
                    if (Boolean.TRUE.equals(row.getConverted())) {
                        converted++;
                    }
                } else if (PushLog.SKIP_LIMIT.equals(row.getSkipReason())) {
                    skippedLimit++;
                } else if (PushLog.SKIP_QUIET.equals(row.getSkipReason())) {
                    skippedQuiet++;
                } else if (PushLog.SKIP_UNSUBSCRIBED.equals(row.getSkipReason())) {
                    skippedOptOut++;
                }
            }
            List<PushLogRow> logRows = withLogs ? logRows(logs) : List.of();
            rows.add(new PushTaskStat(task.getId(), task.getBizDate(), task.getPlanTime(), task.getSentAt(),
                    task.getStatus(), task.getChannel(), task.getRelatedItemId(),
                    task.getRelatedItemId() == null ? null : itemNames.get(task.getRelatedItemId()),
                    task.getRelatedSuggestionId(), task.getTargetCount(), task.getTitle(), task.getReason(),
                    task.getAudienceRule(), delivered, opened, converted, skippedLimit, skippedQuiet,
                    skippedOptOut, groupRows, rate(opened, delivered), rate(converted, delivered), logRows));
        }
        return rows;
    }

    /** 是否按日期过滤：给了 from/to 就按日期；只给 taskId 时按任务精确查、不限日期。 */
    private static boolean dateFiltered(LocalDate from, LocalDate to, Long taskId) {
        return from != null || to != null || taskId == null;
    }

    private List<PushLogRow> logRows(List<PushLog> logs) {
        Map<Long, Member> members = membersOf(logs.stream().map(PushLog::getMemberId).toList());
        List<PushLogRow> rows = new ArrayList<>(logs.size());
        for (PushLog row : logs) {
            Member member = row.getMemberId() == null ? null : members.get(row.getMemberId());
            String label = row.getMemberId() == null ? "全店群发文案"
                    : (member == null ? "会员#" + row.getMemberId() : member.getNickname());
            rows.add(new PushLogRow(row.getId(), row.getTaskId(), row.getMemberId(),
                    member == null ? null : member.getMemberNo(), label, row.getSentAt(),
                    row.getSkipReason() == null && row.getMemberId() != null,
                    Boolean.TRUE.equals(row.getOpened()), row.getOpenedAt(),
                    Boolean.TRUE.equals(row.getConverted()), row.getConvertedAt(),
                    row.getRelatedSaleId(), row.getConvertWindowHours(), row.getSkipReason()));
        }
        return rows;
    }

    private Map<Long, PushTask> tasksOf(List<Long> taskIds) {
        Map<Long, PushTask> map = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        taskIds.stream().filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) {
            return map;
        }
        taskMapper.selectBatchIds(ids).forEach(task -> map.put(task.getId(), task));
        return map;
    }

    private Map<Long, String> itemNames(List<Long> itemIds) {
        Map<Long, String> map = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        itemIds.stream().filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) {
            return map;
        }
        itemMapper.selectBatchIds(ids).forEach(item -> map.put(item.getId(), item.getItemName()));
        return map;
    }

    private Map<Long, Member> membersOf(List<Long> memberIds) {
        Map<Long, Member> map = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        memberIds.stream().filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) {
            return map;
        }
        memberMapper.selectBatchIds(ids).forEach(member -> map.put(member.getId(), member));
        return map;
    }

    /** 比率：4 位小数；分母为 0 时返回 0.0000（并在 note 里说明分母口径）。 */
    static BigDecimal rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    /**
     * 单条销售记录的归因结果。
     *
     * @param logId 命中的 push_log id（未归因为 null）
     */
    public record AttributionResult(Long logId, boolean newlyAttributed, boolean alreadyAttributed, String note) {

        static AttributionResult none(Long logId, String note) {
            return new AttributionResult(logId, false, false, note);
        }

        static AttributionResult newly(PushLog log, String note) {
            return new AttributionResult(log.getId(), true, false, note);
        }

        static AttributionResult already(PushLog log, String note) {
            return new AttributionResult(log.getId(), false, true, note);
        }
    }
}
