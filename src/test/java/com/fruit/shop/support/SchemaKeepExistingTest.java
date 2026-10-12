package com.fruit.shop.support;

import com.fruit.shop.FruitShopApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件库重启不丢数据（Issue #1 队友指出：demo 用文件库，但每次启动都 DROP/CREATE 重建表）。
 * 用临时目录里的 H2 文件库连续启动三次：keep-existing=true 保留数据；=false 显式重置。
 */
class SchemaKeepExistingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("app.db.keep-existing=true：重启后保留已录入数据；=false 时重建为空表")
    void fileDatabaseSurvivesRestartUnlessResetRequested() {
        String url = "jdbc:h2:file:" + tempDir.resolve("keep").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

        try (ConfigurableApplicationContext first = start(url, true)) {
            jdbc(first).update("INSERT INTO fruit_item (item_code, item_name, category_name, unit, cost_price, base_price, shelf_life_days, loss_rate_pct)"
                    + " VALUES ('KEEP-1', '重启保留果', '测试类', '斤', 3.00, 6.00, 5, 5.0)");
        }
        try (ConfigurableApplicationContext second = start(url, true)) {
            assertThat(countKeep(second)).as("keep-existing=true 重启后数据仍在").isEqualTo(1);
        }
        try (ConfigurableApplicationContext reset = start(url, false)) {
            assertThat(countKeep(reset)).as("keep-existing=false 显式重置演示数据").isZero();
        }
    }

    private ConfigurableApplicationContext start(String url, boolean keepExisting) {
        return new SpringApplicationBuilder(FruitShopApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + url,
                "--app.db.keep-existing=" + keepExisting,
                "--app.seed.enabled=false",
                "--app.scan.enabled=false",
                "--spring.h2.console.enabled=false");
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcTemplate.class);
    }

    private static int countKeep(ConfigurableApplicationContext context) {
        Integer count = jdbc(context).queryForObject(
                "SELECT COUNT(*) FROM fruit_item WHERE item_code = 'KEEP-1'", Integer.class);
        return count == null ? 0 : count;
    }
}
