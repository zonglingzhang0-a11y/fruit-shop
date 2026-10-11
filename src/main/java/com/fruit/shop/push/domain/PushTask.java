package com.fruit.shop.push.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 推送任务 = <b>计划</b>（docs/07-与参考架构的差异与补齐.md 第 1 节 C，对应参考架构 push_task）。
 *
 * <p>拆表口径（v2，替代 v1 的单表 {@code push_message}）：
 * <ul>
 *   <li>{@code push_task} 只描述"打算在什么时候、推给谁、推什么"，状态只有 PLANNED/SENT/CANCELLED；</li>
 *   <li>"发出去之后怎么样了"（打开、转化、跳过）全部落 {@link PushLog}，一个任务多行；</li>
 *   <li>本表不存具体会员名单：选人规则以文本落 {@code audience_rule}，发送时按同一规则复算
 *       （名单来自 {@code member_pref}，是派生结果而不是决策结果，避免快照与实际偏好脱节）。</li>
 * </ul>
 *
 * <p>无自动执行（docs/05 NFR-03）：只有显式调用 {@code POST /api/push/preview} 才会建 PLANNED，
 * 只有显式调用 {@code POST /api/push/send} 才会置 SENT；本类不含任何定时逻辑。
 */
@Data
@TableName("push_task")
public class PushTask {

    /** 计划中，尚未发送：允许发送，不占会员每日额度。 */
    public static final String STATUS_PLANNED = "PLANNED";

    /** 已发送：写 sent_at，并为每个目标会员落 push_log；重复 send 幂等（不再写日志）。 */
    public static final String STATUS_SENT = "SENT";

    /** 已取消：不再发送。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate bizDate;

    /** 关联单品（归因"同单品"判定就是拿这个字段和 sales_record.item_id 比）。 */
    private Long relatedItemId;

    private Long relatedBatchId;

    /** 关联的定价建议（决策留痕：建议 → 计划 → 日志 → 成交可串成一条链）。 */
    private Long relatedSuggestionId;

    /** 渠道：IN_APP（个人站内消息）/ GROUP_TEXT（全店群发文案），见 PushPolicy。 */
    private String channel;

    /** 计划发送时间 = 当天高峰小时前 30~60 分钟（沿用 TrafficService"高峰前 1 小时"口径）。 */
    private LocalDateTime planTime;

    /** 为什么是这个时间、为什么是这些人（中文人话，VARCHAR(128)）。 */
    private String reason;

    /** 选人规则文本（docs/01 规则 6），发送时据此复算名单。 */
    private String audienceRule;

    /** 计划内的目标会员数（0 表示只发群发文案）。 */
    private Integer targetCount;

    /** 群发文案标题（个人版文案在发送时按昵称现拼，不落库）。 */
    private String title;

    private String content;

    private String status;

    private LocalDateTime createdAt;

    /** 实际发送时刻；未发送为 null。 */
    private LocalDateTime sentAt;
}
