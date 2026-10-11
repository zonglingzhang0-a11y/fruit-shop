package com.fruit.shop.push;

import com.fruit.shop.push.dto.PushAttributionRequest;
import com.fruit.shop.push.dto.PushAttributionResponse;
import com.fruit.shop.push.dto.PushOpenRequest;
import com.fruit.shop.push.dto.PushOpenResponse;
import com.fruit.shop.push.dto.PushPreviewRequest;
import com.fruit.shop.push.dto.PushPreviewResponse;
import com.fruit.shop.push.dto.PushSendRequest;
import com.fruit.shop.push.dto.PushSendResponse;
import com.fruit.shop.push.dto.PushStatsResponse;
import com.fruit.shop.push.dto.PushTaskStat;
import com.fruit.shop.support.ApiResult;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 推送接口。
 *
 * <p>docs/01-技术方案.md 第 5 节接口 9、10（路径冻结，不改）：
 * <ul>
 *   <li>9. {@code POST /api/push/preview}：生成文案预览 = <b>建计划</b>（push_task, PLANNED）；</li>
 *   <li>10. {@code POST /api/push/send}：发送（写 push_log，守每日上限与静默时段）。</li>
 * </ul>
 *
 * <p>v2 新增（docs/07 第 1 节 C 的补齐项，均为"新增路径"，不动上面两条）：
 * <ul>
 *   <li>{@code GET /api/push/tasks}：查计划与效果明细（含打开/转化/跳过计数）；</li>
 *   <li>{@code GET /api/push/stats}：打开率、转化率（分子分母都在响应里）；</li>
 *   <li>{@code POST /api/push/open}：标记打开（写 opened/opened_at）；</li>
 *   <li>{@code POST /api/push/attribute}：手工重跑 3 小时同单品归因（幂等）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService pushService;
    private final PushEffectService effectService;

    public PushController(PushService pushService, PushEffectService effectService) {
        this.pushService = pushService;
        this.effectService = effectService;
    }

    /** 9. 生成推送文案预览：创建一条 push_task（PLANNED）并返回计划详情，不发送、不占会员每日额度。 */
    @PostMapping("/preview")
    public ApiResult<PushPreviewResponse> preview(@Valid @RequestBody PushPreviewRequest request) {
        return ApiResult.ok(pushService.preview(request));
    }

    /** 10. 发送（推荐传 preview 返回的 taskId；也兼容 messageIds 与 itemId+memberIds 现拼模式）。 */
    @PostMapping("/send")
    public ApiResult<PushSendResponse> send(@Valid @RequestBody PushSendRequest request) {
        return ApiResult.ok(pushService.send(request));
    }

    /** 查计划与效果明细：bizDate 单日，或 from/to 区间（默认最近 7 天）；taskId 必填时返回每行 push_log。 */
    @GetMapping("/tasks")
    public ApiResult<List<PushTaskStat>> tasks(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = bizDate != null ? bizDate : from;
        LocalDate end = bizDate != null ? bizDate : to;
        return ApiResult.ok(effectService.tasks(start, end, taskId, taskId != null));
    }

    /** 效果统计：送达数 / 打开数 / 转化数 / 打开率 / 转化率 + 按任务明细（分子分母都写清楚）。 */
    @GetMapping("/stats")
    public ApiResult<PushStatsResponse> stats(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = bizDate != null ? bizDate : from;
        LocalDate end = bizDate != null ? bizDate : to;
        return ApiResult.ok(effectService.stats(start, end, taskId, taskId != null));
    }

    /** 标记打开：传 logId，或 taskId+memberId；幂等，已打开的保留首次 opened_at。 */
    @PostMapping("/open")
    public ApiResult<PushOpenResponse> open(@RequestBody PushOpenRequest request) {
        return ApiResult.ok(effectService.markOpened(request));
    }

    /** 手工重跑归因（幂等）：saleId / bizDate / from-to 三选一。 */
    @PostMapping("/attribute")
    public ApiResult<PushAttributionResponse> attribute(@RequestBody PushAttributionRequest request) {
        return ApiResult.ok(effectService.attribute(request));
    }
}
