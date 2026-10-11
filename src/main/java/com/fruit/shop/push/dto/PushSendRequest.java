package com.fruit.shop.push.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发送请求（docs/01-技术方案.md 第 5 节接口 10）。
 *
 * <p>v2 拆表后（docs/07 第 1 节 C）三种用法，按优先级：
 * <ol>
 *   <li><b>推荐</b> {@code {taskId}}：发送 {@code POST /api/push/preview} 建好的计划，
 *       发送时按 {@code audience_rule} 复算名单，并遵守推送开关 / 每日上限 / 静默时段；</li>
 *   <li>{@code {messageIds[]}}：v1 兼容字段，拆表后语义 = 计划（push_task）id 列表，等价于 {@code taskIds}；</li>
 *   <li>{@code {bizDate, itemId, discountRate, memberIds[]}}：现拼文案即时发送（docs/01 接口 10 的第二种形态）。
 *       不传 memberIds 时按"买过该品类"的偏好自动选人（规则 6），并同时落一条全店群发文案；
 *       这条路径会先隐式建一条 push_task（reason 注明"接口即时建立"）再发送，保证每次发送都有计划留痕。</li>
 * </ol>
 *
 * <p>{@code bizDate} 现在可选：不传时用被发送计划的 {@code biz_date}（现拼模式用 {@code sendAt} 的日期）。
 * {@code sendAt} 是可选扩展：用于演示/补发指定时刻，不传用系统当前时间（也是测试能确定性验证静默时段的关键）。
 */
@Data
public class PushSendRequest {

    /** 计划 id（推荐用法）：来自 preview 返回的 taskId。 */
    private Long taskId;

    /** 一次发送多个计划（可选）。 */
    private List<Long> taskIds;

    /** v1 兼容字段：拆表后语义 = push_task.id 列表（与 taskIds 等价）。 */
    private List<Long> messageIds;

    /** 业务日（yyyy-MM-dd）；不传按计划/发送时刻推断。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bizDate;

    /** 现拼模式：商品 id。 */
    private Long itemId;

    /** 现拼模式：折扣系数；不传则按最近批次剩余天数用 RULE 档位推算。 */
    private BigDecimal discountRate;

    /** 现拼模式：显式指定会员；不传则按品类偏好自动选人。 */
    private List<Long> memberIds;

    /** 自动选人上限，默认 20，上限 200。 */
    @Min(value = 1, message = "limit 至少为 1")
    @Max(value = 200, message = "limit 最多 200")
    private Integer limit;

    /** 可选：模拟/补发时刻，默认系统当前时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sendAt;

    /**
     * 可选：是否同时落一条全店群发文案（push_log 里 member_id 为空的那行）。
     * 不传时的默认值：计划模式 true（计划本来就有群发文案）、"自动选人"模式 true、"显式指定会员"模式 false。
     */
    private Boolean includeGroupText;
}
