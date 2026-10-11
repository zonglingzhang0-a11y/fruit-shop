package com.fruit.shop.batch;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 Spring 定时任务能力（主类 {@code FruitShopApplication} 属集成方，刻意不动它）。
 *
 * <p>{@code @EnableScheduling} 是容器级开关，放在这里与放在主类效果一致；当前只注册了一个任务
 * ——{@link BatchScanJob#hourlyScan()} 这个**只读**临期扫描（docs/07 第 2 节）。
 * 除它之外的任何模块都不应新增 {@code @Scheduled}（推送/改价必须由显式接口调用触发），
 * 这条约束由 {@code NoAutonomousActionTest} 以白名单方式锁定。
 */
@Configuration
@EnableScheduling
public class BatchScanSchedulingConfig {
}
