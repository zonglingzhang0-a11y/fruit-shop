package com.fruit.shop.support;

import com.fruit.shop.support.mapper.HealthCheckMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 骨架自检接口：/api/health 能返回数据即说明 Web + MyBatis-Plus + H2 全部打通。 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthCheckMapper healthCheckMapper;

    public HealthController(HealthCheckMapper healthCheckMapper) {
        this.healthCheckMapper = healthCheckMapper;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> health() {
        List<HealthCheck> rows = healthCheckMapper.selectList(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", "fruit-shop");
        body.put("status", "UP");
        body.put("checks", rows.stream()
                .map(r -> r.getCheckName() + "=" + r.getStatus())
                .collect(Collectors.toList()));
        return ApiResult.ok(body);
    }
}
