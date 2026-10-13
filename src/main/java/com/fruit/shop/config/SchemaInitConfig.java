package com.fruit.shop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * 建表脚本的执行口径：区分"正常启动"与"重置演示数据"（Issue #1 队友指出的数据丢失风险）。
 *
 * <p>建表脚本（{@code db/schema-h2.sql} / {@code db/schema-mysql.sql}）每张表都是 {@code DROP TABLE IF EXISTS + CREATE TABLE}，
 * 适合内存库（每次都是空库）。但 demo / mysql profile 用的是持久化的库，每次启动都跑这份脚本，
 * 店主录入的库存、定价决策、推送日志都会被清空。
 *
 * <p>由 {@code app.db.keep-existing} 控制：
 * <ul>
 *   <li>{@code false}（默认，内存库与测试）：照旧每次执行建表脚本；</li>
 *   <li>{@code true}（demo 文件库、mysql）：库里已经有完整的表结构就<b>跳过</b>建表脚本、保留数据，
 *       再按 {@link #COLUMNS} / {@link #INDEXES} 给旧库补上后来新加的列和索引（按元数据判断，H2 与 MySQL 通用、可重复执行）；
 *       首次启动（空库）仍会建表，随后 SeedDataLoader 按 seed_load_log 判断是否导入种子；</li>
 *   <li>需要回到干净的演示数据时，显式加启动参数 {@code --app.db.keep-existing=false} 重置一次。</li>
 * </ul>
 *
 * <p>与 Spring Boot 自带的初始化保持同样的开关：{@code spring.sql.init.enabled=false} 时不注册本初始化器，
 * {@code spring.sql.init.mode=never} 时既不跑建表脚本、也不补列。
 *
 * <p><b>以后给表加列/加索引</b>：建表脚本（两份）里加上，同时在 {@link #COLUMNS} / {@link #INDEXES} 里登记一条，
 * 已有的库启动时就会自动补上。改列类型、删列这类变更不在这里做，需要 {@code --app.db.keep-existing=false} 重建。
 */
@Configuration
@EnableConfigurationProperties(SqlInitializationProperties.class)
public class SchemaInitConfig {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitConfig.class);

    /**
     * 用来判断"建表脚本已经完整跑过"的标志表：取建表脚本里<b>最后创建</b>的表。
     * 建表脚本中途被打断时它还不存在，下次启动会重新完整建表，而不是保留半套表结构。
     */
    static final String MARKER_TABLE = "seed_load_log";

    /** 增量列：表、列、补列语句（标准 SQL，H2 与 MySQL 都能执行）。 */
    record ColumnMigration(String table, String column, String ddl) {
    }

    /** 增量索引：表、索引名、建索引语句。 */
    record IndexMigration(String table, String index, String ddl) {
    }

    static final List<ColumnMigration> COLUMNS = List.of(
            // #3 销售回流幂等：业务流水号 + 原始请求数量
            new ColumnMigration("sales_record", "request_no",
                    "ALTER TABLE sales_record ADD COLUMN request_no VARCHAR(64) NULL"),
            new ColumnMigration("sales_record", "requested_qty",
                    "ALTER TABLE sales_record ADD COLUMN requested_qty DECIMAL(10,2) NULL"),
            // 推送计划记住预览折扣：无确认价时只传 taskId 发送，沿用店主预览时看到的价格
            new ColumnMigration("push_task", "discount_rate",
                    "ALTER TABLE push_task ADD COLUMN discount_rate DECIMAL(4,2) NULL"));

    static final List<IndexMigration> INDEXES = List.of(
            new IndexMigration("sales_record", "uk_sales_request_no",
                    "CREATE UNIQUE INDEX uk_sales_request_no ON sales_record (request_no)"));

    @Bean
    @ConditionalOnProperty(name = "spring.sql.init.enabled", havingValue = "true", matchIfMissing = true)
    public SqlDataSourceScriptDatabaseInitializer dataSourceScriptDatabaseInitializer(
            DataSource dataSource,
            SqlInitializationProperties properties,
            @Value("${app.db.keep-existing:false}") boolean keepExisting) {
        return new SqlDataSourceScriptDatabaseInitializer(dataSource, properties) {
            @Override
            public boolean initializeDatabase() {
                if (properties.getMode() == DatabaseInitializationMode.NEVER) {
                    return false;
                }
                // 判断失败时宁可启动失败，也不能当成空库去执行带 DROP TABLE 的建表脚本
                if (keepExisting && tableExists(dataSource, MARKER_TABLE)) {
                    log.info("[db] app.db.keep-existing=true 且库中已有完整表结构，跳过建表脚本、保留现有数据"
                            + "（如需重置演示数据，启动时加 --app.db.keep-existing=false）");
                    migrate(dataSource);
                    return false;
                }
                if (keepExisting) {
                    log.info("[db] 空库首次启动（或上次建表未完成）：执行建表脚本");
                }
                return super.initializeDatabase();
            }
        };
    }

    /** 给保留下来的旧库补上缺的列和索引（已存在的跳过，可重复执行）。 */
    static void migrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            for (ColumnMigration m : COLUMNS) {
                if (!columnExists(connection, m.table(), m.column())) {
                    st.execute(m.ddl());
                    log.info("[db] 旧库补列 {}.{}", m.table(), m.column());
                }
            }
            for (IndexMigration m : INDEXES) {
                if (!indexExists(connection, m.table(), m.index())) {
                    st.execute(m.ddl());
                    log.info("[db] 旧库补索引 {}.{}", m.table(), m.index());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("给已有数据库补列/补索引失败，请检查表结构，或用 --app.db.keep-existing=false 重建："
                    + e.getMessage(), e);
        }
    }

    static boolean tableExists(DataSource dataSource, String table) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            for (String name : nameVariants(table)) {
                // catalog/schema 限定为当前库：MySQL 驱动在 catalog 传 null 时会搜遍服务器上所有库
                try (ResultSet rs = meta.getTables(connection.getCatalog(), connection.getSchema(), name,
                        new String[] {"TABLE"})) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("无法判断表 " + table + " 是否存在，为避免误删数据，停止启动：" + e.getMessage(), e);
        }
    }

    static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String t : nameVariants(table)) {
            for (String c : nameVariants(column)) {
                try (ResultSet rs = meta.getColumns(connection.getCatalog(), connection.getSchema(), t, c)) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean indexExists(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String t : nameVariants(table)) {
            try (ResultSet rs = meta.getIndexInfo(connection.getCatalog(), connection.getSchema(), t, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (name != null && name.equalsIgnoreCase(index)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 不同库对未加引号标识符的大小写处理不同（H2 默认转大写、本项目配置转小写；MySQL 按原样），三种都试。 */
    private static List<String> nameVariants(String name) {
        return List.of(name, name.toLowerCase(Locale.ROOT), name.toUpperCase(Locale.ROOT)).stream().distinct().toList();
    }
}
