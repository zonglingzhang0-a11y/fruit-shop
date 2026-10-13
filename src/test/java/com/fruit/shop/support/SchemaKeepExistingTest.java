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

    @Test
    @DisplayName("保留旧库时执行增量脚本：缺 request_no 列的旧库重启后自动补列和唯一索引，数据不丢")
    void keptDatabaseIsMigratedInPlace() {
        String url = "jdbc:h2:file:" + tempDir.resolve("migrate").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

        try (ConfigurableApplicationContext old = start(url, true)) {
            // 模拟 #3 之前建的旧库：没有 request_no 列
            jdbc(old).execute("DROP INDEX uk_sales_request_no");
            jdbc(old).execute("ALTER TABLE sales_record DROP COLUMN request_no");
            jdbc(old).update("INSERT INTO fruit_item (item_code, item_name, category_name, unit, cost_price, base_price, shelf_life_days, loss_rate_pct)"
                    + " VALUES ('KEEP-2', '迁移保留果', '测试类', '斤', 3.00, 6.00, 5, 5.0)");
            assertThat(columnExists(old, "sales_record", "request_no")).isFalse();
        }
        try (ConfigurableApplicationContext migrated = start(url, true)) {
            assertThat(columnExists(migrated, "sales_record", "request_no")).as("增量脚本补上了新列").isTrue();
            assertThat(jdbc(migrated).queryForObject(
                    "SELECT COUNT(*) FROM fruit_item WHERE item_code = 'KEEP-2'", Integer.class)).isEqualTo(1);
        }
        try (ConfigurableApplicationContext again = start(url, true)) {
            assertThat(columnExists(again, "sales_record", "request_no")).as("增量脚本可重复执行").isTrue();
        }
    }

    private static boolean columnExists(ConfigurableApplicationContext context, String table, String column) {
        Integer count = jdbc(context).queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?",
                Integer.class, table, column);
        return count != null && count > 0;
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
