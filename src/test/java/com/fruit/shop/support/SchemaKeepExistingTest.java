package com.fruit.shop.support;

import com.fruit.shop.FruitShopApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件库重启不丢数据（Issue #1 队友指出：demo 用文件库，但每次启动都 DROP/CREATE 重建表）。
 * 用临时目录里的 H2 文件库连续启动三次：keep-existing=true 保留数据；=false 显式重置。
 */
class SchemaKeepExistingTest {

    /*
     * 本类专测 H2 文件库，不论外面用哪个 profile 跑测试（例如 -Dspring.profiles.active=mysql 把全套测试跑在 MySQL 上），
     * 都显式钉死 H2 的驱动、账号和建表脚本。
     */
    private static final String H2_DRIVER = "--spring.datasource.driver-class-name=org.h2.Driver";
    private static final String H2_USER = "--spring.datasource.username=sa";
    private static final String H2_PASSWORD = "--spring.datasource.password=";
    private static final String H2_SCHEMA = "--spring.sql.init.schema-locations=classpath:db/schema-h2.sql";

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
            assertThat(columnExists(migrated, "sales_record", "request_no")).as("补上了新列").isTrue();
            assertThat(jdbc(migrated).queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = 'uk_sales_request_no'",
                    Integer.class)).as("补上了唯一索引").isEqualTo(1);
            jdbc(migrated).update("INSERT INTO sales_record (biz_date, sale_time, item_id, quantity, unit_price, request_no)"
                    + " VALUES (DATE '2033-01-01', TIMESTAMP '2033-01-01 10:00:00', 1, 1, 1, 'MIG-UNIQ')");
            assertThatThrownBy(() -> jdbc(migrated).update(
                    "INSERT INTO sales_record (biz_date, sale_time, item_id, quantity, unit_price, request_no)"
                            + " VALUES (DATE '2033-01-01', TIMESTAMP '2033-01-01 10:00:00', 1, 1, 1, 'MIG-UNIQ')"))
                    .as("唯一索引真的生效").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertThat(jdbc(migrated).queryForObject(
                    "SELECT COUNT(*) FROM fruit_item WHERE item_code = 'KEEP-2'", Integer.class)).isEqualTo(1);
        }
        try (ConfigurableApplicationContext again = start(url, true)) {
            assertThat(columnExists(again, "sales_record", "request_no")).as("增量脚本可重复执行").isTrue();
        }
    }

    @Test
    @DisplayName("spring.sql.init.mode=never：保留旧库时也不补列、不跑建表脚本，与 Spring Boot 自带开关一致")
    void initModeNeverTouchesNothing() {
        String url = "jdbc:h2:file:" + tempDir.resolve("never").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (ConfigurableApplicationContext old = start(url, true)) {
            jdbc(old).execute("DROP INDEX uk_sales_request_no");
            jdbc(old).execute("ALTER TABLE sales_record DROP COLUMN request_no");
        }
        try (ConfigurableApplicationContext never = new SpringApplicationBuilder(FruitShopApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + url, H2_DRIVER, H2_USER, H2_PASSWORD, H2_SCHEMA,
                "--app.db.keep-existing=true",
                "--spring.sql.init.mode=never", "--app.seed.enabled=false", "--app.scan.enabled=false",
                "--spring.h2.console.enabled=false")) {
            assertThat(columnExists(never, "sales_record", "request_no")).isFalse();
        }
    }

    @Test
    @DisplayName("种子导入中途失败：该文件整体回滚、不留半截数据；修好后重启（保留旧库）能干净重新导入")
    void interruptedSeedImportLeavesNoPartialRows() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("seed").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        Path seedDir = Files.createDirectories(tempDir.resolve("seed-csv"));
        String header = "item_code,item_name,category_name,unit,cost_price,base_price,shelf_life_days,loss_rate_pct\n";
        // 第 3 行与第 1 行 item_code 重复 → 撞唯一索引，模拟"导入到一半失败"
        Files.writeString(seedDir.resolve("shop_item.csv"), header
                + "S001,种子苹果,仁果类,斤,3.0,6.0,10,4.0\n"
                + "S002,种子梨,仁果类,斤,3.0,6.0,10,4.0\n"
                + "S001,重复苹果,仁果类,斤,3.0,6.0,10,4.0\n");

        assertThatThrownBy(() -> startWithSeed(url, seedDir).close()).isNotNull();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            assertThat(count(c, "SELECT COUNT(*) FROM fruit_item WHERE item_code LIKE 'S00%'"))
                    .as("失败的文件整体回滚，前两行也不能留下").isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM seed_load_log WHERE seed_name = 'shop_item'")).isZero();
        }

        Files.writeString(seedDir.resolve("shop_item.csv"), header
                + "S001,种子苹果,仁果类,斤,3.0,6.0,10,4.0\n"
                + "S002,种子梨,仁果类,斤,3.0,6.0,10,4.0\n");
        try (ConfigurableApplicationContext fixed = startWithSeed(url, seedDir)) {
            assertThat(jdbc(fixed).queryForObject(
                    "SELECT COUNT(*) FROM fruit_item WHERE item_code LIKE 'S00%'", Integer.class)).isEqualTo(2);
        }
    }

    private ConfigurableApplicationContext startWithSeed(String url, Path seedDir) {
        return new SpringApplicationBuilder(FruitShopApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + url,
                H2_DRIVER, H2_USER, H2_PASSWORD, H2_SCHEMA,
                "--app.db.keep-existing=true",
                "--app.seed.enabled=true",
                "--app.seed.dir=" + seedDir.toAbsolutePath(),
                "--app.scan.enabled=false",
                "--spring.h2.console.enabled=false");
    }

    private static int count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
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
                H2_DRIVER, H2_USER, H2_PASSWORD, H2_SCHEMA,
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
