package com.fruit.shop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 街边水果店临期果品动态定价与到店客流适配推送系统。
 *
 * 主线链路：入库批次 -> 临期预警 -> 定价建议 -> 店主确认 -> 会员推送 -> 销售回流 -> 策略效果对比
 */
@SpringBootApplication
@MapperScan("com.fruit.shop.**.mapper")
public class FruitShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(FruitShopApplication.class, args);
    }
}
