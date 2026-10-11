package com.fruit.shop.traffic;

import com.fruit.shop.support.ApiResult;
import com.fruit.shop.traffic.dto.HourlyRow;
import com.fruit.shop.traffic.dto.PeakResponse;
import com.fruit.shop.traffic.dto.TrafficProfileResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 客流接口（docs/01-技术方案.md 第 5 节接口 7、8 + docs/07 第 1 节 D 的周内规律）。 */
@RestController
@RequestMapping("/api/traffic")
public class TrafficController {

    private final TrafficService trafficService;
    private final TrafficProfileService profileService;

    public TrafficController(TrafficService trafficService, TrafficProfileService profileService) {
        this.trafficService = trafficService;
        this.profileService = profileService;
    }

    /** 7. 时段客流（date 不传默认今天）；无数据返回空数组，不是错误。 */
    @GetMapping("/hourly")
    public ApiResult<List<HourlyRow>> hourly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(trafficService.hourly(date));
    }

    /** 8. 高峰时段 + 建议推送时间（高峰前 1 小时）。 */
    @GetMapping("/peaks")
    public ApiResult<PeakResponse> peaks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(trafficService.peaks(date));
    }

    /** 9. 周内客流规律（星期 × 小时）查询；weekday 不传返回全部。 */
    @GetMapping("/profile")
    public ApiResult<TrafficProfileResponse> profile(@RequestParam(required = false) Integer weekday) {
        return ApiResult.ok(profileService.query(weekday));
    }

    /**
     * 10. 重算周内客流规律（显式调用才写库；属于派生数据重算，不改任何业务状态）。
     * 可选样本窗口 from/to，不传则用全部历史客流。
     */
    @PostMapping("/profile/recompute")
    public ApiResult<TrafficProfileResponse> recomputeProfile(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResult.ok(profileService.recompute(from, to));
    }
}
