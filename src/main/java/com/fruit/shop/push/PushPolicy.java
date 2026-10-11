package com.fruit.shop.push;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.push.domain.PushLog;
import com.fruit.shop.push.mapper.PushLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 推送规则（docs/01-技术方案.md 第 3 节第 5 条）：
 * 单会员每日上限 {@code app.push.daily-limit-per-member}（默认 2）、静默时段 {@code app.push.quiet-hours}（默认 22:00-08:00）。
 *
 * <p>口径细节（v2 拆表后，容易踩坑，写清楚）：
 * <ul>
 *   <li>限额只统计 {@code push_log} 里 <b>真正送达</b>的行：{@code member_id = 该会员}、
 *       {@code skip_reason IS NULL}、{@code sent_at} 落在该业务日窗口内；
 *       群发文案行（member_id 为空）不占个人额度，被拦下的行（skip_reason 非空）也不占额度——
 *       否则"被限频"这件事会自己把额度越吃越少；</li>
 *   <li>{@code push_log} 没有 biz_date 列，业务日窗口按 {@code sent_at ∈ [当日 00:00, 次日 00:00)} 判断；</li>
 *   <li>静默时段是"左闭右开"：22:00 起算属于静默，08:00 整已经可以发；跨零点用 start &gt; end 判断；</li>
 *   <li>静默时段支持多段（逗号分隔，例如 {@code 12:00-13:00,22:00-08:00}），配置写错时回退默认值而不是让服务起不来。</li>
 * </ul>
 */
@Component
public class PushPolicy {

    /** 个人站内消息（push_task.channel）。 */
    public static final String CHANNEL_IN_APP = "IN_APP";

    /** 全店群发文案（push_task.channel；push_log 里 member_id 为空的行即代表它）。 */
    public static final String CHANNEL_GROUP_TEXT = "GROUP_TEXT";

    private static final Logger log = LoggerFactory.getLogger(PushPolicy.class);

    private static final List<TimeRange> DEFAULT_QUIET = List.of(
            new TimeRange(LocalTime.of(22, 0), LocalTime.of(8, 0)));

    private final PushLogMapper pushLogMapper;
    private final AppProperties props;

    public PushPolicy(PushLogMapper pushLogMapper, AppProperties props) {
        this.pushLogMapper = pushLogMapper;
        this.props = props;
    }

    public int dailyLimit() {
        return Math.max(0, props.getPush().getDailyLimitPerMember());
    }

    public String quietHoursText() {
        return props.getPush().getQuietHours() == null || props.getPush().getQuietHours().isBlank()
                ? "22:00-08:00" : props.getPush().getQuietHours().trim();
    }

    /** 解析静默时段；解析不出来就回退默认 22:00-08:00（并发/配置问题不该让推送接口 500）。 */
    public List<TimeRange> quietRanges() {
        String raw = quietHoursText();
        List<TimeRange> ranges = new ArrayList<>();
        for (String part : raw.split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            String[] bounds = piece.split("-");
            if (bounds.length != 2) {
                log.warn("[push] 无法识别的静默时段片段 '{}'（应为 HH:mm-HH:mm），已忽略", piece);
                continue;
            }
            try {
                ranges.add(new TimeRange(LocalTime.parse(bounds[0].trim()), LocalTime.parse(bounds[1].trim())));
            } catch (DateTimeParseException e) {
                log.warn("[push] 静默时段片段 '{}' 时间格式不对，已忽略", piece);
            }
        }
        return ranges.isEmpty() ? DEFAULT_QUIET : ranges;
    }

    /** 当前时刻是否处于静默时段。 */
    public boolean isQuiet(LocalDateTime moment) {
        LocalDateTime at = moment == null ? LocalDateTime.now() : moment;
        LocalTime time = at.toLocalTime();
        return quietRanges().stream().anyMatch(range -> range.contains(time));
    }

    /** 某会员在某业务日已经<b>真正送达</b>的个人推送条数（跳过行不算）。 */
    public int sentToday(Long memberId, LocalDate bizDate) {
        if (memberId == null) {
            return 0;
        }
        LocalDate day = bizDate == null ? LocalDate.now() : bizDate;
        return pushLogMapper.selectCount(Wrappers.<PushLog>lambdaQuery()
                .eq(PushLog::getMemberId, memberId)
                .isNull(PushLog::getSkipReason)
                .ge(PushLog::getSentAt, day.atStartOfDay())
                .lt(PushLog::getSentAt, day.plusDays(1).atStartOfDay())).intValue();
    }

    /** 某会员在某业务日还可发送条数（不会小于 0）。 */
    public int quotaLeft(Long memberId, LocalDate bizDate) {
        return Math.max(0, dailyLimit() - sentToday(memberId, bizDate));
    }

    /** 静默时段区间：左闭右开；start &gt; end 表示跨零点。 */
    public record TimeRange(LocalTime start, LocalTime end) {

        public boolean contains(LocalTime time) {
            if (start == null || end == null || time == null) {
                return false;
            }
            if (start.equals(end)) {
                return false;
            }
            if (start.isBefore(end)) {
                return !time.isBefore(start) && time.isBefore(end);
            }
            return !time.isBefore(start) || time.isBefore(end);
        }
    }
}
