package com.fruit.shop.push.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推送日志 = <b>发送与效果</b>（docs/07-与参考架构的差异与补齐.md 第 1 节 C，对应参考架构 push_log）。
 *
 * <p>一行 = 一次"针对某个对象的发送尝试"：
 * <ul>
 *   <li>真正送达：{@code skip_reason} 为空，计入<b>送达数</b>（打开率/转化率的分母）；</li>
 *   <li>被规则拦下：{@code skip_reason} 记「限频 / 静默 / 退订」，{@code sent_at} 记尝试时刻，
 *       <b>不计入</b>送达数（见 {@link PushEffectService} 的统计口径）；</li>
 *   <li>群发文案：{@code member_id} 为空，只代表"全店文案发出去了"，不针对个人、不参与个人指标。</li>
 * </ul>
 *
 * <p>归因口径（冻结）：本行发出后 {@code CONVERT_WINDOW_HOURS}（3）小时内，该会员买了<b>同一单品</b>的
 * {@code sales_record} → {@code converted=true}、{@code converted_at}、{@code related_sale_id} 指向那条销售记录；
 * 超窗、跨单品、退货都不归因，且可重复调用幂等。
 */
@Data
@TableName("push_log")
public class PushLog {

    /** 跳过原因：当日推送已达每人上限。 */
    public static final String SKIP_LIMIT = "限频";

    /** 跳过原因：静默时段（默认 22:00-08:00）。 */
    public static final String SKIP_QUIET = "静默";

    /** 跳过原因：会员关闭了推送开关（退订）。 */
    public static final String SKIP_UNSUBSCRIBED = "退订";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 为空表示群发文案（全店级），不是个人推送。 */
    private Long memberId;

    private LocalDateTime sentAt;

    private Boolean opened;

    private LocalDateTime openedAt;

    /** 是否带来购买（窗口内同单品成交）。 */
    private Boolean converted;

    private LocalDateTime convertedAt;

    /** 归因命中的销售记录 id；未转化为 null。同一条销售记录不会挂到多条 log 上。 */
    private Long relatedSaleId;

    /** 归因窗口小时数（默认 3），落库便于事后复核"为什么算转化"。 */
    private Integer convertWindowHours;

    /** 限频/静默/退订；为空表示真正送达。 */
    private String skipReason;
}
