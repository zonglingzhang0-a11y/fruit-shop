package com.fruit.shop.push;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.BatchService;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.item.ItemService;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.domain.MemberPref;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.pricing.PricingPolicy;
import com.fruit.shop.pricing.domain.PricingSuggestion;
import com.fruit.shop.pricing.mapper.PricingSuggestionMapper;
import com.fruit.shop.pricing.strategy.RuleBasedPricingStrategy;
import com.fruit.shop.push.domain.PushLog;
import com.fruit.shop.push.domain.PushTask;
import com.fruit.shop.push.dto.PushCopy;
import com.fruit.shop.push.dto.PushCopyRequest;
import com.fruit.shop.push.dto.PushPreviewRequest;
import com.fruit.shop.push.dto.PushPreviewResponse;
import com.fruit.shop.push.dto.PushSendRequest;
import com.fruit.shop.push.dto.PushSendResponse;
import com.fruit.shop.push.dto.PushSentItem;
import com.fruit.shop.push.dto.PushSkipItem;
import com.fruit.shop.push.dto.PushTarget;
import com.fruit.shop.push.mapper.PushLogMapper;
import com.fruit.shop.push.mapper.PushTaskMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.traffic.TrafficService;
import com.fruit.shop.traffic.dto.PeakResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 站内推送：<b>预览 = 建计划</b>、<b>发送 = 写日志</b>
 * （docs/01-技术方案.md 第 3 节第 5、6 条 + 第 5 节接口 9、10；拆表口径见 docs/07 第 1 节 C）。
 *
 * <p>v2 拆表后本类的职责：
 * <ul>
 *   <li>{@link #preview}：按客流高峰算出 {@code planTime}（高峰前 30~60 分钟，沿用 TrafficService
 *       "高峰前 1 小时"口径），按规则 6 选人，落一条 {@code push_task(status=PLANNED)} 并返回计划详情。
 *       <b>只建计划，不发送、不写 push_log、不占会员每日额度</b>；</li>
 *   <li>{@link #send}：发送指定计划（或现拼一条即时计划再发），为计划 audience 内每个会员写一行
 *       {@code push_log}（群发文案写 member_id 为空的一行），并守既有约束：每人每天最多
 *       {@code app.push.daily-limit-per-member} 条、静默时段 {@code app.push.quiet-hours} 不发。
 *       <ul>
 *         <li>送达的行 {@code skip_reason} 为空；被拦下的行写 {@code 限频/静默/退订}；</li>
 *         <li>在<b>选人阶段</b>就被排除的会员（退订、已超限、无该品类偏好）不写日志行，只在响应 {@code skipped[]} 里说明；</li>
 *         <li>≥1 条送达 → 计划置 SENT 并写 sent_at；全部被拦下 → 保持 PLANNED 可重发；</li>
 *         <li>对已 SENT 的计划重复调用幂等：不再写任何日志行，返回 {@code alreadySent=true}。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>推送对象（docs/01 规则 6）：买过该品类（{@code member_pref} 命中）且 {@code push_enabled=1} 的会员优先单独推送；
 * 没有任何偏好记录的会员只参与"全店级"群发文案（{@code push_log.member_id} 为 NULL），不单独打扰。
 *
 * <p>本类不做任何定时/自动动作：没有任何 {@code @Scheduled}，只有显式调用接口才会写 push_task / push_log
 * （docs/05 NFR-03、docs/07 第 2 节）。文案生成走 {@link PushCopyWriter} 策略口子：注入的是接口，
 * 模板实现只作兜底，因此把"大模型文案"换成真实 LLM 时本类不需要改。
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    /** 预览默认返回的推荐条数。 */
    public static final int DEFAULT_PREVIEW_TARGETS = 10;

    /** 现拼模式自动选人默认上限。 */
    public static final int DEFAULT_AUTO_TARGETS = 20;

    /** 计划时间兜底：当日没有客流数据时，按"默认高峰 10:00 的前 1 小时"计划。 */
    public static final int DEFAULT_PLAN_HOUR = 9;

    private static final String BUCKET_LIMIT = "LIMIT";

    private static final String BUCKET_QUIET = "QUIET";

    private static final String BUCKET_OPT_OUT = "OPT_OUT";

    private static final String BUCKET_GROUP_ONLY = "GROUP_ONLY";

    private static final String BUCKET_OTHER = "OTHER";

    /** push_task.reason 列宽。 */
    private static final int REASON_MAX = 128;

    /** push_task.audience_rule 列宽。 */
    private static final int AUDIENCE_RULE_MAX = 128;

    private static final java.time.format.DateTimeFormatter PUSH_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private final PushTaskMapper taskMapper;
    private final PushLogMapper logMapper;
    private final MemberMapper memberMapper;
    private final MemberPrefMapper memberPrefMapper;
    private final BatchService batchService;
    private final FruitBatchMapper batchMapper;
    private final TrafficService trafficService;
    private final ItemService itemService;
    private final PricingSuggestionMapper suggestionMapper;
    private final PushCopyWriter copyWriter;
    private final TemplatePushCopyWriter templateWriter;
    private final PushPolicy pushPolicy;
    private final AppProperties props;

    public PushService(PushTaskMapper taskMapper,
                       PushLogMapper logMapper,
                       MemberMapper memberMapper,
                       MemberPrefMapper memberPrefMapper,
                       BatchService batchService,
                       FruitBatchMapper batchMapper,
                       TrafficService trafficService,
                       ItemService itemService,
                       PricingSuggestionMapper suggestionMapper,
                       PushCopyWriter copyWriter,
                       TemplatePushCopyWriter templateWriter,
                       PushPolicy pushPolicy,
                       AppProperties props) {
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.memberMapper = memberMapper;
        this.memberPrefMapper = memberPrefMapper;
        this.batchService = batchService;
        this.batchMapper = batchMapper;
        this.trafficService = trafficService;
        this.itemService = itemService;
        this.suggestionMapper = suggestionMapper;
        this.copyWriter = copyWriter;
        this.templateWriter = templateWriter;
        this.pushPolicy = pushPolicy;
        this.props = props;
    }

    // ------------------------------------------------------------------ 9. 预览 = 建计划

    /**
     * 文案预览：<b>创建一条 {@code push_task}（status=PLANNED）</b>并返回计划详情。
     *
     * <p>不发送、不写 push_log、不占会员每日额度——店主确认后再调 {@code POST /api/push/send} 传 taskId。
     * 返回值里的 {@code targets} 是候选会员清单（含"当前能不能发"的逐条说明），
     * {@code candidateMemberIds} 是计划 audience（发送时按同一规则复算）。
     */
    @Transactional
    public PushPreviewResponse preview(PushPreviewRequest request) {
        FruitItem item = itemService.requireItem(request.getItemId());
        LocalDate bizDate = request.getBizDate() == null ? LocalDate.now() : request.getBizDate();
        LocalDateTime moment = request.getSendAt() == null ? LocalDateTime.now() : request.getSendAt();
        int targetLimit = request.getTargetCount() == null || request.getTargetCount() <= 0
                ? DEFAULT_PREVIEW_TARGETS : request.getTargetCount();

        FruitBatch batch = nearestOnSaleBatch(item.getId());
        Integer daysLeft = batch == null ? null : BatchService.daysLeft(bizDate, batch.getExpireDate());
        BigDecimal rate = resolveDiscount(request.getDiscountRate(), daysLeft);
        BigDecimal price = PricingPolicy.priceOf(item.getBasePrice(), rate);
        PricingSuggestion suggestion = latestSuggestion(bizDate, item.getId());

        // 计划时间：当天高峰小时前 30~60 分钟（TrafficService 给的是"高峰前 1 小时"）
        PeakResponse peaks = trafficService.peaks(bizDate);
        Integer suggestPushHour = firstPeakHour(peaks);
        PlanTime plan = resolvePlanTime(bizDate, peaks, suggestPushHour);
        String timeHint = timeHint(suggestPushHour, peaks);

        PushCopy groupCopy = writeCopy(new PushCopyRequest(
                props.getPush().getStoreName(), item.getItemName(), item.getCategoryName(), item.getUnit(),
                item.getBasePrice(), price, rate, PricingPolicy.discountLabel(rate), daysLeft,
                batch == null ? null : batch.getExpireDate(), batch == null ? null : batch.getRemainQty(),
                null, timeHint, PushPolicy.CHANNEL_GROUP_TEXT));

        boolean quiet = pushPolicy.isQuiet(moment);
        List<MemberPref> prefs = prefsOfCategory(item.getCategoryName());
        Map<Long, Member> memberById = membersOf(prefs.stream().map(MemberPref::getMemberId).toList());

        List<PushTarget> targets = new ArrayList<>();
        List<PushSkipItem> skipped = new ArrayList<>();
        int candidateCount = 0;
        int sendableCount = 0;
        for (MemberPref pref : prefs) {
            Member member = memberById.get(pref.getMemberId());
            if (member == null) {
                continue;
            }
            if (!isPushEnabled(member)) {
                // 选人阶段就排除：不写 push_log 行，只在 skipped[] 里说明（退订）
                skipped.add(new PushSkipItem(member.getId(), member.getNickname(), member.getMemberNo(),
                        "会员已关闭推送开关（退订），不单独推送", BUCKET_OPT_OUT, PushLog.SKIP_UNSUBSCRIBED));
                continue;
            }
            candidateCount++;
            int sentToday = pushPolicy.sentToday(member.getId(), bizDate);
            int quotaLeft = Math.max(0, pushPolicy.dailyLimit() - sentToday);
            Block block = blockReason(member, sentToday, quiet);
            boolean sendable = block == null;
            if (sendable) {
                sendableCount++;
            } else {
                skipped.add(new PushSkipItem(member.getId(), member.getNickname(), member.getMemberNo(),
                        block.reason(), block.bucket(), block.skipCode()));
            }

            String reason = String.format("买过「%s」%d 次，偏好分 %s",
                    pref.getCategoryName(), pref.getBuyCount() == null ? 0 : pref.getBuyCount(),
                    pref.getScore() == null ? "0" : pref.getScore().toPlainString());
            if (!sendable) {
                reason = reason + "；本次不发：" + block.reason();
            }
            if (targets.size() >= targetLimit) {
                continue;
            }
            PushCopy personal = writeCopy(new PushCopyRequest(
                    props.getPush().getStoreName(), item.getItemName(), item.getCategoryName(), item.getUnit(),
                    item.getBasePrice(), price, rate, PricingPolicy.discountLabel(rate), daysLeft,
                    batch == null ? null : batch.getExpireDate(), batch == null ? null : batch.getRemainQty(),
                    member.getNickname(), timeHint, PushPolicy.CHANNEL_IN_APP));
            targets.add(new PushTarget(member.getId(), member.getMemberNo(), member.getNickname(),
                    member.getPhoneTail(), pref.getCategoryName(), pref.getScore(), pref.getBuyCount(), reason,
                    true, sentToday, quotaLeft, sendable, block == null ? null : block.reason(),
                    personal.title(), personal.content(), block == null ? null : block.skipCode()));
        }

        // 规则 6：没有该品类偏好记录的会员不单独推，只参与全店群发文案
        int groupOnly = groupOnlyMemberCount(prefs);
        if (groupOnly > 0) {
            skipped.add(new PushSkipItem(null, "全店群发文案覆盖 " + groupOnly + " 位无偏好会员", null,
                    "这些会员没有「" + item.getCategoryName() + "」偏好记录，按规则只参与全店群发文案，不单独推送",
                    BUCKET_GROUP_ONLY, null));
        }

        // 建计划（PLANNED）：只落 push_task，不发送
        PushTask task = new PushTask();
        task.setBizDate(bizDate);
        task.setRelatedItemId(item.getId());
        task.setRelatedBatchId(batch == null ? null : batch.getId());
        task.setRelatedSuggestionId(suggestion == null ? null : suggestion.getId());
        task.setChannel(PushPolicy.CHANNEL_IN_APP);
        task.setPlanTime(plan.time());
        task.setReason(planReason(plan, candidateCount, item.getCategoryName(), targets.size(), groupOnly));
        task.setAudienceRule(audienceRuleText(item.getCategoryName(), targets.size()));
        task.setTargetCount(targets.size());
        task.setTitle(groupCopy.title());
        task.setContent(groupCopy.content());
        task.setStatus(PushTask.STATUS_PLANNED);
        taskMapper.insert(task);

        List<Long> candidateIds = targets.stream().map(PushTarget::memberId).toList();
        log.info("[push] 建计划 taskId={} bizDate={} planTime={} 候选={} 可发={} 计划目标={} 建议={} 草稿=false",
                task.getId(), bizDate, plan.time(), candidateCount, sendableCount, targets.size(),
                suggestion == null ? null : suggestion.getId());

        return new PushPreviewResponse(
                groupCopy.title(), groupCopy.content(), targets.size(), targets,
                item.getId(), item.getItemName(), item.getCategoryName(), item.getUnit(), item.getBasePrice(),
                rate, price, PricingPolicy.discountLabel(rate), daysLeft,
                batch == null ? null : batch.getExpireDate(), batch == null ? null : batch.getBatchNo(),
                bizDate, props.getPush().getStoreName(), suggestPushHour,
                plan.time().format(PUSH_TIME_FORMAT), peaks.peakHours(), timeHint,
                candidateCount, sendableCount, groupOnly, copyWriter.writerName(), skipped,
                List.of(task.getId()),
                task.getId(), task.getPlanTime(), task.getReason(), task.getAudienceRule(), task.getStatus(),
                task.getRelatedSuggestionId(), candidateIds,
                "预览只写一条 push_task（status=PLANNED）：不发送、不写 push_log、不占会员每日额度；"
                        + "确认后用 POST /api/push/send 传 taskId 发送。");
    }

    // ------------------------------------------------------------------ 10. 发送 = 写日志

    /**
     * 发送：把一个 {@code push_task} 置为 SENT（写 sent_at），并为计划 audience 内每个会员写一条 push_log。
     *
     * @see PushSendRequest 三种用法（taskId 推荐 / messageIds 兼容 / itemId 现拼）
     */
    @Transactional
    public PushSendResponse send(PushSendRequest request) {
        LocalDateTime sendAt = request.getSendAt() == null ? LocalDateTime.now() : request.getSendAt();
        boolean quiet = pushPolicy.isQuiet(sendAt);
        Outcome tally = new Outcome();
        List<String> notes = new ArrayList<>();
        List<Long> handledTaskIds = new ArrayList<>();
        Long primaryTaskId = null;
        String primaryTaskStatus = null;
        LocalDateTime primaryPlanTime = null;
        boolean alreadySent = false;
        LocalDate responseBizDate = request.getBizDate();

        List<Long> planIds = planIdsOf(request);
        if (!planIds.isEmpty()) {
            for (Long planId : planIds) {
                PushTask task = taskMapper.selectById(planId);
                if (task == null) {
                    tally.skippedOther++;
                    tally.skipped.add(new PushSkipItem(null, null, null,
                            "计划不存在：taskId=" + planId, BUCKET_OTHER, null));
                    continue;
                }
                if (primaryTaskId == null) {
                    primaryTaskId = task.getId();
                    primaryPlanTime = task.getPlanTime();
                }
                handledTaskIds.add(task.getId());
                if (responseBizDate == null) {
                    responseBizDate = task.getBizDate();
                }
                if (PushTask.STATUS_SENT.equals(task.getStatus())) {
                    alreadySent = true;
                    tally.skippedOther++;
                    tally.skipped.add(new PushSkipItem(null, "计划 #" + task.getId() + " 已发送", null,
                            "该计划已发送过（幂等：不再重复写 push_log），如需再发请重新预览建计划",
                            BUCKET_OTHER, null));
                    primaryTaskStatus = task.getStatus();
                    continue;
                }
                if (PushTask.STATUS_CANCELLED.equals(task.getStatus())) {
                    tally.skippedOther++;
                    tally.skipped.add(new PushSkipItem(null, "计划 #" + task.getId() + " 已取消", null,
                            "计划已取消，不再发送", BUCKET_OTHER, null));
                    primaryTaskStatus = task.getStatus();
                    continue;
                }

                FruitItem item = task.getRelatedItemId() == null ? null : itemService.requireItem(task.getRelatedItemId());
                if (item == null) {
                    tally.skippedOther++;
                    tally.skipped.add(new PushSkipItem(null, "计划 #" + task.getId() + " 未关联单品", null,
                            "计划没有关联单品（related_item_id 为空），无法复算人群与文案，拒绝发送",
                            BUCKET_OTHER, null));
                    primaryTaskStatus = task.getStatus();
                    continue;
                }
                LocalDate taskBizDate = request.getBizDate() != null ? request.getBizDate() : task.getBizDate();
                List<Member> audience = planAudience(task, item);
                boolean includeGroup = request.getIncludeGroupText() == null
                        || Boolean.TRUE.equals(request.getIncludeGroupText());
                Outcome outcome = sendTask(task, item, audience, taskBizDate, sendAt, quiet, includeGroup,
                        request.getDiscountRate());
                tally.merge(outcome);
                primaryTaskStatus = task.getStatus();
                notes.add("计划 #" + task.getId() + "（" + task.getBizDate() + "）按选人规则复算 "
                        + audience.size() + " 位目标会员，状态 " + task.getStatus());
            }
        } else if (request.getItemId() != null) {
            // 现拼模式（docs/01 接口 10 的第二种形态）：先隐式建一条计划再发送，保证每次发送都有计划留痕
            FruitItem item = itemService.requireItem(request.getItemId());
            LocalDate adhocDate = request.getBizDate() != null ? request.getBizDate() : sendAt.toLocalDate();
            responseBizDate = adhocDate;
            boolean autoSelect = request.getMemberIds() == null || request.getMemberIds().isEmpty();
            List<Member> audience = new ArrayList<>();
            if (autoSelect) {
                int limit = request.getLimit() == null || request.getLimit() <= 0 ? DEFAULT_AUTO_TARGETS : request.getLimit();
                audience = autoTargets(item.getCategoryName(), limit);
            } else {
                Map<Long, Member> found = membersOf(request.getMemberIds());
                for (Long memberId : new LinkedHashSet<>(request.getMemberIds())) {
                    Member member = found.get(memberId);
                    if (member == null) {
                        tally.skippedOther++;
                        tally.skipped.add(new PushSkipItem(memberId, null, null,
                                "会员不存在：memberId=" + memberId, BUCKET_OTHER, null));
                        continue;
                    }
                    audience.add(member);
                }
            }
            PeakResponse peaks = trafficService.peaks(adhocDate);
            Integer pushHour = firstPeakHour(peaks);
            String hint = timeHint(pushHour, peaks);

            FruitBatch batch = nearestOnSaleBatch(item.getId());
            Integer daysLeft = batch == null ? null : BatchService.daysLeft(adhocDate, batch.getExpireDate());
            BigDecimal rate = resolveDiscount(request.getDiscountRate(), daysLeft);
            PushCopy groupCopy = writeCopy(new PushCopyRequest(
                    props.getPush().getStoreName(), item.getItemName(), item.getCategoryName(), item.getUnit(),
                    item.getBasePrice(), PricingPolicy.priceOf(item.getBasePrice(), rate), rate,
                    PricingPolicy.discountLabel(rate), daysLeft,
                    batch == null ? null : batch.getExpireDate(), batch == null ? null : batch.getRemainQty(),
                    null, hint, PushPolicy.CHANNEL_GROUP_TEXT));

            PushTask task = new PushTask();
            task.setBizDate(adhocDate);
            task.setRelatedItemId(item.getId());
            task.setRelatedBatchId(batch == null ? null : batch.getId());
            task.setRelatedSuggestionId(null);
            task.setChannel(PushPolicy.CHANNEL_IN_APP);
            task.setPlanTime(sendAt);
            task.setAudienceRule(audienceRuleText(item.getCategoryName(), audience.size()));
            task.setReason(truncate("接口即时建立并发送（未经过 preview）；"
                    + audienceRuleText(item.getCategoryName(), audience.size()), REASON_MAX));
            task.setTargetCount(audience.size());
            task.setTitle(groupCopy.title());
            task.setContent(groupCopy.content());
            task.setStatus(PushTask.STATUS_PLANNED);
            taskMapper.insert(task);

            boolean includeGroup = request.getIncludeGroupText() != null
                    ? Boolean.TRUE.equals(request.getIncludeGroupText()) : autoSelect;
            Outcome outcome = sendTask(task, item, audience, adhocDate, sendAt, quiet, includeGroup,
                    request.getDiscountRate());
            tally.merge(outcome);
            primaryTaskId = task.getId();
            primaryPlanTime = task.getPlanTime();
            primaryTaskStatus = task.getStatus();
            handledTaskIds.add(task.getId());
            notes.add("现拼模式：即时建立计划 #" + task.getId() + " 并发送，目标 "
                    + audience.size() + " 位会员（" + (autoSelect ? "按品类偏好自动选人" : "显式指定会员") + "）");
        } else {
            throw BusinessException.badRequest(
                    "请传 taskId（由 /api/push/preview 建计划后发送），或传 itemId+memberIds/discountRate 现拼文案发送");
        }

        int sent = tally.sentPersonal + tally.sentGroup;
        if (sent == 0 && tally.skippedLimit + tally.skippedQuiet + tally.skippedUnsub > 0) {
            notes.add("本次 0 条送达（全部被限频/静默/退订拦下），计划保持 PLANNED 可重发；"
                    + "被拦下的行已写 push_log.skip_reason，不计入送达数");
        }
        if (sent > 0) {
            notes.add("已送达 " + tally.sentPersonal + " 条个人推送 + " + tally.sentGroup + " 条群发文案；"
                    + "归因窗口 " + PushEffectService.CONVERT_WINDOW_HOURS + " 小时（同会员同单品），"
                    + "成交时由 POST /api/sales/feedback 自动触发匹配");
        }
        log.info("[push] 发送完成 taskId={} bizDate={} sendAt={} 送达={}（个人 {} + 群发 {}）跳过：限频 {} 静默 {} 退订 {} 其它 {}",
                primaryTaskId, responseBizDate, sendAt, sent, tally.sentPersonal, tally.sentGroup,
                tally.skippedLimit, tally.skippedQuiet, tally.skippedUnsub, tally.skippedOther);

        return new PushSendResponse(
                sent, tally.skippedLimit, tally.skippedQuiet, tally.logIds, responseBizDate, sendAt,
                tally.sentPersonal, tally.sentGroup, tally.skippedOther, tally.groupLogId, quiet,
                pushPolicy.quietHoursText(), pushPolicy.dailyLimit(), tally.sentItems, tally.skipped,
                primaryTaskId, handledTaskIds, primaryTaskStatus, primaryPlanTime, alreadySent,
                tally.skippedUnsub, PushEffectService.CONVERT_WINDOW_HOURS, String.join("；", notes));
    }

    /** 发送一个计划：为 audience 内每个会员写 push_log；≥1 条送达才置 SENT（全部被拦下保持 PLANNED 可重发）。 */
    private Outcome sendTask(PushTask task, FruitItem item, List<Member> audience, LocalDate bizDate,
                             LocalDateTime sendAt, boolean quiet, boolean includeGroupText, BigDecimal requestedRate) {
        Outcome outcome = new Outcome();
        FruitBatch batch = nearestOnSaleBatch(item.getId());
        Integer daysLeft = batch == null ? null : BatchService.daysLeft(bizDate, batch.getExpireDate());
        BigDecimal rate = resolveDiscount(requestedRate, daysLeft);
        BigDecimal price = PricingPolicy.priceOf(item.getBasePrice(), rate);
        PeakResponse peaks = trafficService.peaks(bizDate);
        String hint = timeHint(firstPeakHour(peaks), peaks);

        for (Member member : audience) {
            int sentToday = pushPolicy.sentToday(member.getId(), bizDate);
            Block block = blockReason(member, sentToday, quiet);
            if (block != null) {
                // 计划 audience 内的会员被拦下：写一行带 skip_reason 的尝试记录（不计入送达数）
                PushLog row = writeSkipRow(task.getId(), member, sendAt, block.skipCode());
                outcome.logIds.add(row.getId());
                outcome.skipped.add(new PushSkipItem(member.getId(), member.getNickname(), member.getMemberNo(),
                        block.reason(), block.bucket(), block.skipCode()));
                if (BUCKET_LIMIT.equals(block.bucket())) {
                    outcome.skippedLimit++;
                } else if (BUCKET_QUIET.equals(block.bucket())) {
                    outcome.skippedQuiet++;
                } else if (BUCKET_OPT_OUT.equals(block.bucket())) {
                    outcome.skippedUnsub++;
                } else {
                    outcome.skippedOther++;
                }
                continue;
            }
            PushCopy copy = writeCopy(new PushCopyRequest(
                    props.getPush().getStoreName(), item.getItemName(), item.getCategoryName(), item.getUnit(),
                    item.getBasePrice(), price, rate, PricingPolicy.discountLabel(rate), daysLeft,
                    batch == null ? null : batch.getExpireDate(), batch == null ? null : batch.getRemainQty(),
                    member.getNickname(), hint, PushPolicy.CHANNEL_IN_APP));
            PushLog row = newLog(task.getId(), member.getId(), sendAt, null);
            logMapper.insert(row);
            outcome.logIds.add(row.getId());
            outcome.sentPersonal++;
            outcome.sentItems.add(new PushSentItem(row.getId(), task.getId(), member.getId(), member.getNickname(),
                    PushPolicy.CHANNEL_IN_APP, copy.title(), copy.content(), sendAt));
        }

        if (includeGroupText) {
            if (quiet) {
                outcome.skippedQuiet++;
                outcome.skipped.add(new PushSkipItem(null, "全店群发文案", null,
                        "当前处于静默时段（" + pushPolicy.quietHoursText() + "），不发群发文案",
                        BUCKET_QUIET, PushLog.SKIP_QUIET));
            } else {
                PushLog row = newLog(task.getId(), null, sendAt, null);
                logMapper.insert(row);
                outcome.logIds.add(row.getId());
                outcome.groupLogId = row.getId();
                outcome.sentGroup++;
                outcome.sentItems.add(new PushSentItem(row.getId(), task.getId(), null, "全店群发文案",
                        PushPolicy.CHANNEL_GROUP_TEXT, task.getTitle(), task.getContent(), sendAt));
            }
        }

        if (outcome.sentPersonal + outcome.sentGroup > 0) {
            task.setStatus(PushTask.STATUS_SENT);
            task.setSentAt(sendAt);
            taskMapper.updateById(task);
        } else {
            log.info("[push] 计划 #{} 本次 0 条送达（限频 {} 静默 {} 退订 {}），保持 PLANNED 可重发",
                    task.getId(), outcome.skippedLimit, outcome.skippedQuiet, outcome.skippedUnsub);
        }
        return outcome;
    }

    // ------------------------------------------------------------------ 工具

    /** 要发送的计划 id（taskId / taskIds / v1 兼容 messageIds，后者语义=计划 id）。 */
    private static List<Long> planIdsOf(PushSendRequest request) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (request.getTaskId() != null) {
            ids.add(request.getTaskId());
        }
        if (request.getTaskIds() != null) {
            request.getTaskIds().stream().filter(Objects::nonNull).forEach(ids::add);
        }
        if (request.getMessageIds() != null) {
            request.getMessageIds().stream().filter(Objects::nonNull).forEach(ids::add);
        }
        return new ArrayList<>(ids);
    }

    /** 按 push_task.audience_rule 复算名单：该品类偏好且开启推送的前 targetCount 位（与预览同一规则）。 */
    private List<Member> planAudience(PushTask task, FruitItem item) {
        if (task.getTargetCount() == null || task.getTargetCount() <= 0) {
            return List.of();
        }
        List<MemberPref> prefs = prefsOfCategory(item.getCategoryName());
        Map<Long, Member> memberById = membersOf(prefs.stream().map(MemberPref::getMemberId).toList());
        List<Member> audience = new ArrayList<>();
        for (MemberPref pref : prefs) {
            Member member = memberById.get(pref.getMemberId());
            if (member == null || !isPushEnabled(member)) {
                continue;
            }
            audience.add(member);
            if (audience.size() >= task.getTargetCount()) {
                break;
            }
        }
        return audience;
    }

    /** 送达行：skip_reason 为空，计入送达数（打开率/转化率分母）。 */
    private PushLog newLog(Long taskId, Long memberId, LocalDateTime sentAt, String skipReason) {
        PushLog row = new PushLog();
        row.setTaskId(taskId);
        row.setMemberId(memberId);
        row.setSentAt(sentAt);
        row.setOpened(false);
        row.setConverted(false);
        // 每条 log 自带归因口径，事后可复核"为什么算/不算转化"
        row.setConvertWindowHours(PushEffectService.CONVERT_WINDOW_HOURS);
        row.setSkipReason(skipReason);
        return row;
    }

    /**
     * 被拦下的尝试记录：同一计划 + 同一会员只保留一行（重发时更新尝试时刻），
     * 避免"静默时段反复点发送"在 push_log 里堆出一串重复跳过行。
     */
    private PushLog writeSkipRow(Long taskId, Member member, LocalDateTime attemptAt, String skipReason) {
        Long memberId = member == null ? null : member.getId();
        PushLog existing = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, taskId)
                .eq(memberId != null, PushLog::getMemberId, memberId)
                .isNull(memberId == null, PushLog::getMemberId)
                .isNotNull(PushLog::getSkipReason)
                .orderByDesc(PushLog::getId)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setSentAt(attemptAt);
            existing.setSkipReason(skipReason);
            logMapper.updateById(existing);
            return existing;
        }
        PushLog row = newLog(taskId, memberId, attemptAt, skipReason);
        logMapper.insert(row);
        return row;
    }

    /** 生成文案：优先用注入的策略实现（将来是 LLM），失败或返回空则回退模板，绝不因文案问题阻断发送。 */
    private PushCopy writeCopy(PushCopyRequest request) {
        try {
            PushCopy copy = copyWriter.write(request);
            if (copy != null && copy.title() != null && copy.content() != null) {
                return copy;
            }
            log.warn("[push] 文案实现 {} 返回空，回退模板实现", copyWriter.writerName());
        } catch (RuntimeException e) {
            log.warn("[push] 文案实现 {} 生成失败，回退模板实现：{}", copyWriter.writerName(), e.getMessage());
        }
        return templateWriter.write(request);
    }

    /** 阻挡原因（null = 可以发）。bucket 用于响应里区分跳过类型，skipCode 落 push_log.skip_reason。 */
    private Block blockReason(Member member, int sentToday, boolean quiet) {
        if (member == null) {
            return new Block("会员不存在", BUCKET_OTHER, null);
        }
        if (!isPushEnabled(member)) {
            return new Block("会员已关闭推送开关（退订）", BUCKET_OPT_OUT, PushLog.SKIP_UNSUBSCRIBED);
        }
        if (sentToday >= pushPolicy.dailyLimit()) {
            return new Block("今日推送已达上限（" + pushPolicy.dailyLimit() + " 条）", BUCKET_LIMIT, PushLog.SKIP_LIMIT);
        }
        if (quiet) {
            return new Block("当前处于静默时段（" + pushPolicy.quietHoursText() + "）", BUCKET_QUIET, PushLog.SKIP_QUIET);
        }
        return null;
    }

    private static boolean isPushEnabled(Member member) {
        return member != null && !Boolean.FALSE.equals(member.getPushEnabled());
    }

    /** 计划时间：高峰小时 - 1（docs/01 接口 8 的"高峰前 1 小时"口径，落在 30~60 分钟区间内）。 */
    private PlanTime resolvePlanTime(LocalDate bizDate, PeakResponse peaks, Integer pushHour) {
        if (pushHour != null) {
            String peakText = peaks == null || peaks.peakHours().isEmpty()
                    ? "" : "高峰 " + joinHours(peaks.peakHours()) + "，";
            return new PlanTime(bizDate.atTime(pushHour, 0), peakText + "提前 1 小时（60 分钟）推送");
        }
        return new PlanTime(bizDate.atTime(DEFAULT_PLAN_HOUR, 0),
                "当日无客流数据、无法定位高峰，暂按默认高峰 10:00 的前 1 小时（09:00）计划");
    }

    private Integer firstPeakHour(PeakResponse peaks) {
        if (peaks == null) {
            return null;
        }
        if (!peaks.suggestPushHours().isEmpty()) {
            return peaks.suggestPushHours().get(0);
        }
        if (!peaks.suggestPushTimes().isEmpty()) {
            String text = peaks.suggestPushTimes().get(0);
            try {
                return Integer.parseInt(text.substring(0, 2));
            } catch (RuntimeException e) {
                log.warn("[push] 无法解析建议推送时间 '{}'，改用默认计划时间", text);
            }
        }
        return null;
    }

    /** 为什么这个时间 + 为什么这些人（dump 进 push_task.reason，超 128 截断）。 */
    private String planReason(PlanTime plan, int candidateCount, String categoryName, int planTargets, int groupOnly) {
        String audiencePart = planTargets == 0
                ? String.format("没有会员买过「%s」（或已退订/超限），本次只有全店群发文案", categoryName)
                : String.format("%d 位会员买过「%s」且开启推送，按偏好分取前 %d 位单独推送",
                        candidateCount, categoryName, planTargets);
        String tail = groupOnly > 0 ? "；其余 " + groupOnly + " 位无该品类偏好，只进群发文案" : "";
        return truncate(plan.reason() + "；" + audiencePart + tail, REASON_MAX);
    }

    /** 选人规则文本（落 push_task.audience_rule，发送时按它复算名单）。 */
    private String audienceRuleText(String categoryName, int targetCount) {
        return truncate(String.format(
                "买过「%s」且 push_enabled=1 的会员优先（member_pref 按偏好分降序取前 %d 位单独推送）；"
                        + "无该品类偏好记录的会员只参与全店群发文案（docs/01 规则 6）",
                categoryName, targetCount), AUDIENCE_RULE_MAX);
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }

    private String timeHint(Integer pushHour, PeakResponse peaks) {
        if (pushHour == null) {
            return null;
        }
        String peakText = peaks == null || peaks.peakHours().isEmpty()
                ? "" : "（当日高峰 " + joinHours(peaks.peakHours()) + " 的前 1 小时）";
        return String.format("%s 前后到店人最多%s", String.format("%02d:00", pushHour), peakText);
    }

    private static String joinHours(List<Integer> hours) {
        List<String> parts = new ArrayList<>(hours.size());
        for (Integer hour : hours) {
            parts.add(String.format("%02d:00", hour));
        }
        return String.join("、", parts);
    }

    /** 最近一个在售批次（剩余天数最少、最急的那个），用于算剩余天数与文案。 */
    private FruitBatch nearestOnSaleBatch(Long itemId) {
        return batchMapper.selectOne(Wrappers.<FruitBatch>lambdaQuery()
                .eq(FruitBatch::getItemId, itemId)
                .eq(FruitBatch::getStatus, "ON_SALE")
                .gt(FruitBatch::getRemainQty, BigDecimal.ZERO)
                .orderByAsc(FruitBatch::getExpireDate)
                .last("LIMIT 1"));
    }

    /** 折扣：显式传了就用（并对齐候选档），否则按剩余天数用 RULE 档位，再退到固定档。 */
    private BigDecimal resolveDiscount(BigDecimal requested, Integer daysLeft) {
        if (requested != null) {
            return PricingPolicy.toCandidate(requested);
        }
        if (daysLeft != null) {
            return RuleBasedPricingStrategy.baseDiscount(daysLeft);
        }
        return PricingPolicy.toCandidate(props.getPricing().getFixedDiscount());
    }

    /** 该业务日该单品最近一条定价建议（只用于把"建议 → 计划 → 日志 → 成交"串成一条链）。 */
    private PricingSuggestion latestSuggestion(LocalDate bizDate, Long itemId) {
        return suggestionMapper.selectOne(Wrappers.<PricingSuggestion>lambdaQuery()
                .eq(PricingSuggestion::getBizDate, bizDate)
                .eq(PricingSuggestion::getItemId, itemId)
                .orderByDesc(PricingSuggestion::getId)
                .last("LIMIT 1"));
    }

    private List<MemberPref> prefsOfCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return List.of();
        }
        return memberPrefMapper.selectList(Wrappers.<MemberPref>lambdaQuery()
                .eq(MemberPref::getCategoryName, categoryName)
                .orderByDesc(MemberPref::getScore)
                .orderByDesc(MemberPref::getBuyCount));
    }

    /** 自动选人：命中品类偏好且开启了推送的会员，按偏好分降序。 */
    private List<Member> autoTargets(String categoryName, int limit) {
        List<MemberPref> prefs = prefsOfCategory(categoryName);
        if (prefs.isEmpty()) {
            log.info("[push] 品类 {} 没有会员偏好记录，本次只发全店群发文案", categoryName);
            return List.of();
        }
        Map<Long, Member> memberById = membersOf(prefs.stream().map(MemberPref::getMemberId).toList());
        List<Member> targets = new ArrayList<>();
        for (MemberPref pref : prefs) {
            Member member = memberById.get(pref.getMemberId());
            if (member != null && isPushEnabled(member)) {
                targets.add(member);
            }
            if (targets.size() >= limit) {
                break;
            }
        }
        return targets;
    }

    private Map<Long, Member> membersOf(List<Long> memberIds) {
        Map<Long, Member> map = new HashMap<>();
        List<Long> ids = memberIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        memberMapper.selectBatchIds(ids).forEach(member -> map.put(member.getId(), member));
        return map;
    }

    /** 开启了推送、但没有该品类偏好记录的会员数（规则 6：只能被群发文案覆盖）。 */
    private int groupOnlyMemberCount(List<MemberPref> prefs) {
        long enabledTotal = memberMapper.selectCount(Wrappers.<Member>lambdaQuery()
                .eq(Member::getPushEnabled, true));
        Map<Long, Member> matched = membersOf(prefs.stream().map(MemberPref::getMemberId).toList());
        long matchedEnabled = matched.values().stream().filter(PushService::isPushEnabled).count();
        return (int) Math.max(0, enabledTotal - matchedEnabled);
    }

    /** 阻挡原因（null = 可以发）：bucket 给响应，skipCode 落 push_log.skip_reason。 */
    private record Block(String reason, String bucket, String skipCode) {
    }

    /** 计划时间 + 依据。 */
    private record PlanTime(LocalDateTime time, String reason) {
    }

    /** 一次发送的累计结果（多计划合并后返回）。 */
    private static final class Outcome {

        private final List<Long> logIds = new ArrayList<>();

        private final List<PushSentItem> sentItems = new ArrayList<>();

        private final List<PushSkipItem> skipped = new ArrayList<>();

        private int sentPersonal;

        private int sentGroup;

        private int skippedLimit;

        private int skippedQuiet;

        private int skippedUnsub;

        private int skippedOther;

        private Long groupLogId;

        private void merge(Outcome other) {
            logIds.addAll(other.logIds);
            sentItems.addAll(other.sentItems);
            skipped.addAll(other.skipped);
            sentPersonal += other.sentPersonal;
            sentGroup += other.sentGroup;
            skippedLimit += other.skippedLimit;
            skippedQuiet += other.skippedQuiet;
            skippedUnsub += other.skippedUnsub;
            skippedOther += other.skippedOther;
            if (other.groupLogId != null) {
                groupLogId = other.groupLogId;
            }
        }
    }
}
