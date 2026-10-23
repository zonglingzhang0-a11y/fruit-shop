package com.fruit.shop.push;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.push.domain.PushLog;
import com.fruit.shop.push.domain.PushTask;
import com.fruit.shop.push.dto.PushPreviewRequest;
import com.fruit.shop.push.dto.PushPreviewResponse;
import com.fruit.shop.push.dto.PushSendRequest;
import com.fruit.shop.push.dto.PushSendResponse;
import com.fruit.shop.push.dto.PushSkipItem;
import com.fruit.shop.push.dto.PushTarget;
import com.fruit.shop.push.mapper.PushLogMapper;
import com.fruit.shop.push.mapper.PushTaskMapper;
import com.fruit.shop.support.BusinessException;
import com.fruit.shop.support.ShopFixtures;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 推送计划与日志测试（docs/07 第 1 节 C：push_task 只存计划、push_log 存发送与效果）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>预览 = 建计划：落一条 push_task(PLANNED) + planTime = 高峰前 1 小时 + reason 写清依据；
 *       预览不发送、不写 push_log、不占会员每日额度；</li>
 *   <li>发送 = 写日志：计划置 SENT + 每个目标会员一行 push_log + 群发文案一行（member_id 为空）；</li>
 *   <li>频次规则：每人每天最多 2 条（第 3 条被拦并写 skip_reason=限频）；</li>
 *   <li>静默时段 22:00-08:00：整体不发、计划保持 PLANNED 可重发、跳过行写 skip_reason=静默；</li>
 *   <li>退订：push_enabled=0 的会员被拦，skip_reason=退订；</li>
 *   <li>规则 6：无该品类偏好的会员只被群发文案覆盖，不写个人日志行；</li>
 *   <li>幂等：已 SENT 的计划重复发送不再写日志。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushServiceTest {

    @Autowired
    private PushService pushService;

    @Autowired
    private PushPolicy pushPolicy;

    @Autowired
    private FruitItemMapper itemMapper;

    @Autowired
    private FruitBatchMapper batchMapper;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private MemberPrefMapper memberPrefMapper;

    @Autowired
    private TrafficHourlyMapper trafficMapper;

    @Autowired
    private PushTaskMapper taskMapper;

    @Autowired
    private PushLogMapper logMapper;

    @Test
    @DisplayName("预览=建计划：PLANNED + planTime=高峰前1小时 + reason 写清时间与人群；预览不发送不占额度")
    void previewCreatesPlanAndSendWritesLogs() {
        LocalDate bizDate = LocalDate.of(2038, 3, 1);
        FruitItem item = ShopFixtures.item(itemMapper, "计划测试草莓", "测试计划类A", "8.00", "20.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "8.00");
        Member member = ShopFixtures.member(memberMapper, "计划会员A", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "9.500", 6);
        // 当日高峰 10:00 → 计划时间 = 高峰前 1 小时 = 09:00
        ShopFixtures.traffic(trafficMapper, bizDate, 10, 100, 80, true);

        PushPreviewResponse preview = preview(item.getId(), bizDate, LocalDateTime.of(2038, 3, 1, 9, 0), null, true);

        assertThat(preview.taskId()).isNotNull();
        assertThat(preview.planStatus()).isEqualTo(PushTask.STATUS_PLANNED);
        assertThat(preview.planTime()).isEqualTo(LocalDateTime.of(2038, 3, 1, 9, 0));
        assertThat(preview.reason()).contains("高峰 10:00").contains("提前 1 小时")
                .contains("买过").contains("测试计划类A");
        assertThat(preview.audienceRule()).contains("push_enabled=1").contains("规则 6");
        assertThat(preview.targetCount()).isEqualTo(1);
        assertThat(preview.candidateMemberIds()).containsExactly(member.getId());
        assertThat(preview.messageIds()).containsExactly(preview.taskId());
        assertThat(preview.sendTimeHint()).contains("09:00");

        // 预览只建计划：push_task 一行 PLANNED、没有 sent_at、push_log 一行都没有、不占每日额度
        PushTask planned = taskMapper.selectById(preview.taskId());
        assertThat(planned.getStatus()).isEqualTo(PushTask.STATUS_PLANNED);
        assertThat(planned.getSentAt()).isNull();
        assertThat(planned.getBizDate()).isEqualTo(bizDate);
        assertThat(planned.getRelatedItemId()).isEqualTo(item.getId());
        assertThat(planned.getTargetCount()).isEqualTo(1);
        assertThat(planned.getTitle()).isNotBlank();
        assertThat(logsOf(planned.getId())).isEmpty();
        assertThat(pushPolicy.sentToday(member.getId(), bizDate)).isZero();
        assertThat(pushPolicy.quotaLeft(member.getId(), bizDate)).isEqualTo(2);

        // 发送：计划置 SENT + 1 条个人日志 + 1 条群发日志（member_id 为空）
        PushSendResponse response = pushService.send(planSend(planned.getId(), LocalDateTime.of(2038, 3, 1, 9, 30), null));

        assertThat(response.sent()).isEqualTo(2);
        assertThat(response.sentPersonal()).isEqualTo(1);
        assertThat(response.sentGroup()).isEqualTo(1);
        assertThat(response.taskStatus()).isEqualTo(PushTask.STATUS_SENT);
        assertThat(response.alreadySent()).isFalse();
        assertThat(response.messageIds()).hasSize(2);
        assertThat(response.convertWindowHours()).isEqualTo(3);
        assertThat(response.groupMessageId()).isNotNull();
        assertThat(response.note()).contains("归因窗口 3 小时");

        PushTask sent = taskMapper.selectById(planned.getId());
        assertThat(sent.getStatus()).isEqualTo(PushTask.STATUS_SENT);
        assertThat(sent.getSentAt()).isEqualTo(LocalDateTime.of(2038, 3, 1, 9, 30));

        PushLog personal = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, planned.getId())
                .eq(PushLog::getMemberId, member.getId())
                .last("LIMIT 1"));
        assertThat(personal).isNotNull();
        assertThat(personal.getSkipReason()).isNull();
        assertThat(personal.getSentAt()).isEqualTo(LocalDateTime.of(2038, 3, 1, 9, 30));
        assertThat(personal.getOpened()).isFalse();
        assertThat(personal.getConverted()).isFalse();
        assertThat(personal.getConvertWindowHours()).isEqualTo(3);

        PushLog group = logMapper.selectById(response.groupMessageId());
        assertThat(group.getTaskId()).isEqualTo(planned.getId());
        assertThat(group.getMemberId()).isNull();
        assertThat(group.getSkipReason()).isNull();

        assertThat(pushPolicy.sentToday(member.getId(), bizDate)).isEqualTo(1);
    }

    @Test
    @DisplayName("频次：同一会员同一天最多 2 条，第 3 条被拦并写 skip_reason=限频（跳过行不占额度）")
    void dailyLimitBlocksThirdPush() {
        LocalDate bizDate = LocalDate.of(2038, 3, 2);
        FruitItem item = ShopFixtures.item(itemMapper, "限频测试草莓", "测试限频类B", "8.00", "20.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "20", "8.00");
        Member member = ShopFixtures.member(memberMapper, "限频会员B", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "9.500", 6);

        PushSendResponse first = pushService.send(adhocSend(bizDate, item.getId(), member.getId(),
                LocalDateTime.of(2038, 3, 2, 9, 0)));
        pushService.send(adhocSend(bizDate, item.getId(), member.getId(), LocalDateTime.of(2038, 3, 2, 9, 1)));
        PushSendResponse third = pushService.send(adhocSend(bizDate, item.getId(), member.getId(),
                LocalDateTime.of(2038, 3, 2, 9, 2)));

        assertThat(first.sent()).isEqualTo(1);
        assertThat(first.sentGroup()).isZero();
        assertThat(third.sent()).isZero();
        assertThat(third.skippedByLimit()).isEqualTo(1);
        assertThat(third.skipped()).extracting(PushSkipItem::reason)
                .anySatisfy(reason -> assertThat(reason).contains("上限"));
        assertThat(third.skipped()).extracting(PushSkipItem::skipReason).contains(PushLog.SKIP_LIMIT);

        assertThat(pushPolicy.sentToday(member.getId(), bizDate)).isEqualTo(2);

        // 被拦下的会员在 push_log 里留一行"尝试记录"，带 skip_reason，但不计入送达
        PushLog blocked = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getMemberId, member.getId())
                .eq(PushLog::getSkipReason, PushLog.SKIP_LIMIT)
                .last("LIMIT 1"));
        assertThat(blocked).isNotNull();
        assertThat(blocked.getConvertWindowHours()).isEqualTo(3);
        assertThat(blocked.getConverted()).isFalse();

        // 第 4 次同样被挡（上限不是"只挡一次"，且跳过行不会把额度越吃越少）
        PushSendResponse fourth = pushService.send(adhocSend(bizDate, item.getId(), member.getId(),
                LocalDateTime.of(2038, 3, 2, 9, 3)));
        assertThat(fourth.sent()).isZero();
        assertThat(fourth.skippedByLimit()).isEqualTo(1);
        assertThat(pushPolicy.sentToday(member.getId(), bizDate)).isEqualTo(2);
    }

    @Test
    @DisplayName("静默时段：23:30 不发、计划保持 PLANNED 可重发、跳过行写 skip_reason=静默；08:00 边界可发")
    void quietHoursSkipKeepsPlanReusable() {
        LocalDate bizDate = LocalDate.of(2038, 3, 3);
        FruitItem item = ShopFixtures.item(itemMapper, "静默测试葡萄", "测试静默类C", "6.00", "15.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(2), 5, "10", "6.00");
        Member member = ShopFixtures.member(memberMapper, "静默会员C", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "8.000", 3);

        PushPreviewResponse preview = preview(item.getId(), bizDate, LocalDateTime.of(2038, 3, 3, 9, 0), null, true);
        PushSendResponse quiet = pushService.send(planSend(preview.taskId(),
                LocalDateTime.of(2038, 3, 3, 23, 30), false));

        assertThat(quiet.sent()).isZero();
        assertThat(quiet.skippedByQuietHours()).isEqualTo(1);
        assertThat(quiet.quietNow()).isTrue();
        assertThat(quiet.taskStatus()).isEqualTo(PushTask.STATUS_PLANNED);
        assertThat(quiet.note()).contains("保持 PLANNED");

        PushTask stillPlanned = taskMapper.selectById(preview.taskId());
        assertThat(stillPlanned.getStatus()).isEqualTo(PushTask.STATUS_PLANNED);
        assertThat(stillPlanned.getSentAt()).isNull();
        assertThat(logsOf(preview.taskId())).singleElement()
                .satisfies(row -> assertThat(row.getSkipReason()).isEqualTo(PushLog.SKIP_QUIET));

        // 静默结束（08:00 整是"左闭右开"的边界，可以发）→ 重发同一计划成功
        PushSendResponse retry = pushService.send(planSend(preview.taskId(),
                LocalDateTime.of(2038, 3, 3, 8, 0), false));
        assertThat(retry.sent()).isEqualTo(1);
        assertThat(retry.taskStatus()).isEqualTo(PushTask.STATUS_SENT);
        assertThat(taskMapper.selectById(preview.taskId()).getSentAt()).isEqualTo(LocalDateTime.of(2038, 3, 3, 8, 0));

        // 跳过行被复用（不重复堆积）：1 行跳过 + 1 行送达
        assertThat(logsOf(preview.taskId())).hasSize(2);
        assertThat(deliveredLogsOf(preview.taskId())).hasSize(1);

        assertThat(pushPolicy.dailyLimit()).isEqualTo(2);
        assertThat(pushPolicy.isQuiet(LocalDateTime.of(2038, 3, 3, 23, 59))).isTrue();
        assertThat(pushPolicy.isQuiet(LocalDateTime.of(2038, 3, 3, 7, 59))).isTrue();
        assertThat(pushPolicy.isQuiet(LocalDateTime.of(2038, 3, 3, 22, 0))).isTrue();
        assertThat(pushPolicy.isQuiet(LocalDateTime.of(2038, 3, 3, 8, 0))).isFalse();
        assertThat(pushPolicy.isQuiet(LocalDateTime.of(2038, 3, 3, 12, 0))).isFalse();
    }

    @Test
    @DisplayName("退订（push_enabled=0）被拦下并写 skip_reason=退订；其余会员照常收到")
    void disabledMemberIsSkippedWithOptOutReason() {
        LocalDate bizDate = LocalDate.of(2038, 3, 4);
        FruitItem item = ShopFixtures.item(itemMapper, "开关测试橙", "测试开关类D", "5.00", "12.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(1), 5, "10", "5.00");
        Member disabled = ShopFixtures.member(memberMapper, "关闭推送会员D", false);
        Member enabled = ShopFixtures.member(memberMapper, "正常会员D", true);
        ShopFixtures.pref(memberPrefMapper, disabled.getId(), item.getCategoryName(), "9.000", 9);
        ShopFixtures.pref(memberPrefMapper, enabled.getId(), item.getCategoryName(), "5.000", 2);

        PushSendRequest request = new PushSendRequest();
        request.setBizDate(bizDate);
        request.setItemId(item.getId());
        request.setMemberIds(List.of(disabled.getId(), enabled.getId()));
        request.setSendAt(LocalDateTime.of(2038, 3, 4, 10, 0));
        request.setIncludeGroupText(false);
        PushSendResponse response = pushService.send(request);

        assertThat(response.sent()).isEqualTo(1);
        assertThat(response.sentPersonal()).isEqualTo(1);
        assertThat(response.skippedByUnsubscribed()).isEqualTo(1);
        assertThat(response.skipped()).extracting(PushSkipItem::reason)
                .anySatisfy(reason -> assertThat(reason).contains("退订"));
        assertThat(response.skipped()).extracting(PushSkipItem::skipReason).contains(PushLog.SKIP_UNSUBSCRIBED);

        PushLog optedOut = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, response.taskId())
                .eq(PushLog::getMemberId, disabled.getId())
                .last("LIMIT 1"));
        assertThat(optedOut).isNotNull();
        assertThat(optedOut.getSkipReason()).isEqualTo(PushLog.SKIP_UNSUBSCRIBED);
        assertThat(pushPolicy.sentToday(disabled.getId(), bizDate)).isZero();
        assertThat(pushPolicy.sentToday(enabled.getId(), bizDate)).isEqualTo(1);
    }

    @Test
    @DisplayName("规则 6：自动选人只推命中品类偏好的会员，无偏好会员不写个人日志、只被群发文案覆盖")
    void autoSelectOnlyTargetsMembersWithCategoryPreference() {
        LocalDate bizDate = LocalDate.of(2038, 3, 5);
        FruitItem item = ShopFixtures.item(itemMapper, "选人测试蓝莓", "测试选人类E", "9.00", "22.00", 4);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(1), 4, "10", "9.00");
        Member hit = ShopFixtures.member(memberMapper, "买过蓝莓的会员E", true);
        Member noPref = ShopFixtures.member(memberMapper, "没有任何偏好的会员E", true);
        ShopFixtures.pref(memberPrefMapper, hit.getId(), item.getCategoryName(), "7.500", 4);

        PushSendRequest request = new PushSendRequest();
        request.setBizDate(bizDate);
        request.setItemId(item.getId());
        request.setSendAt(LocalDateTime.of(2038, 3, 5, 9, 30));
        PushSendResponse response = pushService.send(request);

        assertThat(response.sentPersonal()).isEqualTo(1);
        assertThat(response.sentGroup()).isEqualTo(1);
        assertThat(response.sent()).isEqualTo(2);
        assertThat(response.groupMessageId()).isNotNull();
        assertThat(response.taskId()).isNotNull();

        PushLog personal = logMapper.selectOne(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, response.taskId())
                .eq(PushLog::getMemberId, hit.getId())
                .last("LIMIT 1"));
        assertThat(personal).isNotNull();
        assertThat(personal.getSkipReason()).isNull();

        PushLog group = logMapper.selectById(response.groupMessageId());
        assertThat(group.getMemberId()).isNull();

        // 无偏好会员：不进计划、不写个人日志行；群发文案存在计划里（push_log 只记"发出去了"）
        assertThat(logMapper.selectCount(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, response.taskId())
                .eq(PushLog::getMemberId, noPref.getId()))).isZero();
        PushTask task = taskMapper.selectById(response.taskId());
        assertThat(task.getTargetCount()).isEqualTo(1);
        assertThat(task.getAudienceRule()).contains("规则 6");
        assertThat(task.getTitle()).isNotBlank();
        assertThat(task.getContent()).contains("蓝莓");
    }

    @Test
    @DisplayName("计划 audience 上限：targetCount=2 时只给偏好分最高的 2 位建计划并发送")
    void planAudienceIsLimitedByTargetCount() {
        LocalDate bizDate = LocalDate.of(2038, 3, 7);
        FruitItem item = ShopFixtures.item(itemMapper, "限流测试奇异果", "测试限流类F", "7.00", "18.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "12", "7.00");
        Member top = ShopFixtures.member(memberMapper, "高分会员F", true);
        Member second = ShopFixtures.member(memberMapper, "次分会员F", true);
        Member third = ShopFixtures.member(memberMapper, "低分会员F", true);
        ShopFixtures.pref(memberPrefMapper, top.getId(), item.getCategoryName(), "9.900", 9);
        ShopFixtures.pref(memberPrefMapper, second.getId(), item.getCategoryName(), "8.800", 8);
        ShopFixtures.pref(memberPrefMapper, third.getId(), item.getCategoryName(), "1.100", 1);

        PushPreviewResponse preview = preview(item.getId(), bizDate, LocalDateTime.of(2038, 3, 7, 9, 0), 2, true);

        assertThat(preview.targetCount()).isEqualTo(2);
        assertThat(preview.candidateCount()).isEqualTo(3);
        assertThat(preview.candidateMemberIds()).containsExactly(top.getId(), second.getId());
        assertThat(preview.targets()).extracting(PushTarget::memberId).containsExactly(top.getId(), second.getId());
        assertThat(taskMapper.selectById(preview.taskId()).getTargetCount()).isEqualTo(2);

        PushSendResponse response = pushService.send(planSend(preview.taskId(),
                LocalDateTime.of(2038, 3, 7, 9, 30), false));
        assertThat(response.sentPersonal()).isEqualTo(2);
        assertThat(response.sentGroup()).isZero();
        assertThat(deliveredLogsOf(preview.taskId())).hasSize(2);
        // 第三位（低分）不在计划内，不写任何行
        assertThat(logMapper.selectCount(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, preview.taskId())
                .eq(PushLog::getMemberId, third.getId()))).isZero();
    }

    @Test
    @DisplayName("幂等：已 SENT 的计划再次发送不再写日志（alreadySent=true）")
    void resendSentPlanIsIdempotent() {
        LocalDate bizDate = LocalDate.of(2038, 3, 6);
        FruitItem item = ShopFixtures.item(itemMapper, "幂等测试柚", "测试幂等类G", "6.00", "16.00", 5);
        ShopFixtures.batch(batchMapper, item.getId(), bizDate.minusDays(3), 5, "15", "6.00");
        Member member = ShopFixtures.member(memberMapper, "幂等会员G", true);
        ShopFixtures.pref(memberPrefMapper, member.getId(), item.getCategoryName(), "6.000", 5);

        PushPreviewResponse preview = preview(item.getId(), bizDate, LocalDateTime.of(2038, 3, 6, 9, 0), null, true);
        PushSendResponse first = pushService.send(planSend(preview.taskId(),
                LocalDateTime.of(2038, 3, 6, 9, 30), null));
        long rowsAfterFirst = logsOf(preview.taskId()).size();

        PushSendResponse again = pushService.send(planSend(preview.taskId(),
                LocalDateTime.of(2038, 3, 6, 9, 40), null));

        assertThat(first.sent()).isEqualTo(2);
        assertThat(again.sent()).isZero();
        assertThat(again.alreadySent()).isTrue();
        assertThat(again.skippedByOther()).isEqualTo(1);
        assertThat(again.skipped()).extracting(PushSkipItem::reason)
                .anySatisfy(reason -> assertThat(reason).contains("已发送"));
        assertThat(logsOf(preview.taskId())).hasSize((int) rowsAfterFirst);
    }

    @Test
    @DisplayName("既没有 taskId 也没有 itemId → 400 人话报错（不静默什么都不做）")
    void sendWithoutPlanOrItemIsRejected() {
        assertThatThrownBy(() -> pushService.send(new PushSendRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("taskId");
    }

    // ------------------------------------------------------------------ 工具

    private PushPreviewResponse preview(Long itemId, LocalDate bizDate, LocalDateTime sendAt,
                                        Integer targetCount, Boolean persistDraft) {
        PushPreviewRequest request = new PushPreviewRequest();
        request.setItemId(itemId);
        request.setBizDate(bizDate);
        request.setSendAt(sendAt);
        request.setTargetCount(targetCount);
        request.setDiscountRate(new BigDecimal("0.60"));
        request.setPersistDraft(persistDraft);
        return pushService.preview(request);
    }

    private PushSendRequest planSend(Long taskId, LocalDateTime sendAt, Boolean includeGroupText) {
        PushSendRequest request = new PushSendRequest();
        request.setTaskId(taskId);
        request.setSendAt(sendAt);
        request.setIncludeGroupText(includeGroupText);
        return request;
    }

    private PushSendRequest adhocSend(LocalDate bizDate, Long itemId, Long memberId, LocalDateTime sendAt) {
        PushSendRequest request = new PushSendRequest();
        request.setBizDate(bizDate);
        request.setItemId(itemId);
        request.setSendAt(sendAt);
        request.setMemberIds(List.of(memberId));
        request.setIncludeGroupText(false);
        return request;
    }

    private List<PushLog> logsOf(Long taskId) {
        return logMapper.selectList(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, taskId)
                .orderByAsc(PushLog::getId));
    }

    private List<PushLog> deliveredLogsOf(Long taskId) {
        return logMapper.selectList(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getTaskId, taskId)
                .isNull(PushLog::getSkipReason)
                .isNotNull(PushLog::getMemberId)
                .orderByAsc(PushLog::getId));
    }
}
