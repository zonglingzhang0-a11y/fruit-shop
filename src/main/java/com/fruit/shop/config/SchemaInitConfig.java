package com.fruit.shop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.StringUtils;

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
 * <p>与 Spring Boot 自带的初始化保持同样的开关和账号口径：
 * <ul>
 *   <li>{@code spring.sql.init.enabled=false} 时不注册本初始化器；</li>
 *   <li>{@code spring.sql.init.mode=never}，或 {@code embedded} 而当前不是内嵌库（MySQL、H2 文件库都不算内嵌）时，
 *       既不跑建表脚本、也不补列；</li>
 *   <li>配了 {@code spring.sql.init.username/password} 时，建表、补列、判断表是否存在都用这个账号
 *       （应用连接池可以只给增删改查权限）。</li>
 * </ul>
 *
 * <p><b>以后给表加列/加索引</b>：建表脚本（两份）里加上，同时在 {@link #COLUMNS} / {@link #INDEXES} 里登记一条，
 * 已有的库启动时就会自动补上。改列类型、删列这类变更不在这里做，需要 {@code --app.db.keep-existing=false} 重建。
 */
@Configuration
@EnableConfigurationProperties(SqlInitializationProperties.class)
public class SchemaInitConfig {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitConfig.class);

    /**
     * 用来判断"建表脚本已经完整跑过"的标志表：建表脚本<b>第一条就删掉它、最后才重新创建它</b>。
     * 不论是空库首次建表还是 {@code keep-existing=false} 重置，脚本中途被打断时它都不存在，
     * 下次启动会重新完整建表，而不是把半套表当成完整结构。
     */
    static final String MARKER_TABLE = "seed_load_log";

    /**
     * 增量列：表、列、补列语句（标准 SQL，H2 与 MySQL 都能执行）；
     * {@code mysqlDdl} 不为空时 MySQL/MariaDB 用它（例如需要指定排序规则的列）。
     */
    record ColumnMigration(String table, String column, String ddl, String mysqlDdl) {

        ColumnMigration(String table, String column, String ddl) {
            this(table, column, ddl, null);
        }

        String ddlFor(boolean mysql) {
            return mysql && mysqlDdl != null ? mysqlDdl : ddl;
        }
    }

    /** sales_record.request_no 在 MySQL 上的列定义：幂等键必须区分大小写（见 schema-mysql.sql 的说明）。 */
    static final String MYSQL_REQUEST_NO_COLUMN =
            "request_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '业务流水号（幂等键）'";

    /** 增量索引：表、索引名、建索引语句。 */
    record IndexMigration(String table, String index, String ddl) {
    }

    static final List<ColumnMigration> COLUMNS = List.of(
            // #3 销售回流幂等：业务流水号 + 原始请求数量
            new ColumnMigration("sales_record", "request_no",
                    "ALTER TABLE sales_record ADD COLUMN request_no VARCHAR(64) NULL",
                    "ALTER TABLE sales_record ADD COLUMN " + MYSQL_REQUEST_NO_COLUMN),
            new ColumnMigration("sales_record", "requested_qty",
                    "ALTER TABLE sales_record ADD COLUMN requested_qty DECIMAL(10,2) NULL"),
            // #2 定价建议记录实际给出价格的策略（请求 MODEL 但回退规则时为 RULE）
            new ColumnMigration("pricing_suggestion", "effective_strategy",
                    "ALTER TABLE pricing_suggestion ADD COLUMN effective_strategy VARCHAR(16) NULL"),
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
        DataSource initDataSource = initDataSource(dataSource, properties);
        return new SqlDataSourceScriptDatabaseInitializer(initDataSource, properties) {
            @Override
            public boolean initializeDatabase() {
                DatabaseInitializationMode mode = properties.getMode();
                if (mode == DatabaseInitializationMode.NEVER
                        || (mode == DatabaseInitializationMode.EMBEDDED && !isEmbeddedDatabase())) {
                    return false;
                }
                // 判断失败时宁可启动失败，也不能当成空库去执行带 DROP TABLE 的建表脚本
                if (keepExisting && tableExists(initDataSource, MARKER_TABLE)) {
                    log.info("[db] app.db.keep-existing=true 且库中已有完整表结构，跳过建表脚本、保留现有数据"
                            + "（如需重置演示数据，启动时加 --app.db.keep-existing=false）");
                    migrate(initDataSource);
                    // 回填是改数据（UPDATE），用应用账号做：DDL 账号按文档只需要建表/改表权限
                    backfill(dataSource);
                    return false;
                }
                if (keepExisting) {
                    log.info("[db] 空库首次启动（或上次建表未完成）：执行建表脚本");
                }
                return super.initializeDatabase();
            }
        };
    }

    /**
     * 建表/补列用的数据源：与 Spring Boot 自带初始化同一口径——同时配了 {@code spring.sql.init.username} 和
     * {@code password} 就用它们另建一个直连数据源（DDL 账号），否则沿用应用的连接池。
     */
    static DataSource initDataSource(DataSource dataSource, SqlInitializationProperties properties) {
        if (StringUtils.hasText(properties.getUsername()) && StringUtils.hasText(properties.getPassword())) {
            return DataSourceBuilder.derivedFrom(dataSource)
                    .username(properties.getUsername())
                    .password(properties.getPassword())
                    .type(SimpleDriverDataSource.class)
                    .build();
        }
        return dataSource;
    }

    /** 给保留下来的旧库补上缺的列和索引（已存在的跳过，可重复执行）。 */
    static void migrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            boolean mysql = isMySql(connection);
            for (ColumnMigration m : COLUMNS) {
                if (!columnExists(connection, m.table(), m.column())) {
                    st.execute(m.ddlFor(mysql));
                    log.info("[db] 旧库补列 {}.{}", m.table(), m.column());
                }
            }
            for (IndexMigration m : INDEXES) {
                if (!indexExists(connection, m.table(), m.index())) {
                    st.execute(m.ddl());
                    log.info("[db] 旧库补索引 {}.{}", m.table(), m.index());
                }
            }
            if (mysql) {
                fixMySqlRequestNoCollation(connection, st);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("给已有数据库补列/补索引失败，请检查表结构，或用 --app.db.keep-existing=false 重建："
                    + e.getMessage(), e);
        }
    }

    /** 旧数据回填（只改数据、不改表结构），走应用自己的连接池。 */
    static void backfill(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            backfillEffectiveStrategy(st);
        } catch (SQLException e) {
            throw new IllegalStateException("回填旧建议的实际生效策略失败：" + e.getMessage(), e);
        }
    }

    /**
     * 补列之前生成的建议没有 effective_strategy：按理由文字回填（MODEL 回退过规则版的理由里一定写着"已回退规则版"），
     * 让建议列表和此后的销售归属对新旧数据用同一个口径。只动 NULL 行，可重复执行。
     * <p>不重算 strategy_daily_stat：升级前已累加进看板的销售仍按当时记的策略统计（该表还含种子导入的报废、
     * 缺货数据，无法从销售流水重建），这一点写在 docs/03 §3.8。
     */
    static void backfillEffectiveStrategy(Statement st) throws SQLException {
        int n = st.executeUpdate("UPDATE pricing_suggestion SET effective_strategy = CASE"
                + " WHEN strategy = 'MODEL' AND reason LIKE '%已回退规则版%' THEN 'RULE' ELSE strategy END"
                + " WHERE effective_strategy IS NULL");
        if (n > 0) {
            log.info("[db] 旧建议回填实际生效策略 {} 条", n);
        }
    }

    /** MySQL 与 MariaDB（同一套方言）。 */
    static boolean isMySql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && (product.toLowerCase(Locale.ROOT).contains("mysql")
                || product.toLowerCase(Locale.ROOT).contains("mariadb"));
    }

    /** 已有 MySQL 库里 request_no 若是不区分大小写的排序规则，改成二进制比较（幂等键必须区分大小写）。 */
    private static void fixMySqlRequestNoCollation(Connection connection, Statement st) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT COLLATION_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_record' AND COLUMN_NAME = 'request_no'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && !"utf8mb4_bin".equalsIgnoreCase(rs.getString(1))) {
                st.execute("ALTER TABLE sales_record MODIFY " + MYSQL_REQUEST_NO_COLUMN);
                log.info("[db] sales_record.request_no 排序规则 {} → utf8mb4_bin（幂等键区分大小写）", rs.getString(1));
            }
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
