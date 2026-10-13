package com.fruit.shop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 建表脚本的执行口径：区分"正常启动"与"重置演示数据"（Issue #1 队友指出的数据丢失风险）。
 *
 * <p>{@code db/schema-h2.sql} 每张表都是 {@code DROP TABLE IF EXISTS + CREATE TABLE}，适合内存库（每次都是空库）。
 * 但 demo profile 用的是 H2 文件库，原来每次启动都会跑这份脚本，店主录入的库存、定价决策、推送日志都会被清空。
 *
 * <p>现在由 {@code app.db.keep-existing} 控制：
 * <ul>
 *   <li>{@code false}（默认，内存库与测试）：照旧每次执行建表脚本；</li>
 *   <li>{@code true}（demo 文件库）：库里已经有业务表就<b>跳过</b>建表脚本、保留数据；
 *       首次启动（空库）仍会建表，随后 SeedDataLoader 按 seed_load_log 判断是否导入种子；</li>
 *   <li>需要回到干净的演示数据时，显式加启动参数 {@code --app.db.keep-existing=false} 重置一次。</li>
 * </ul>
 * 跳过建表脚本时会执行 {@code db/migrate-h2.sql}（只含可重复执行的 IF NOT EXISTS 增量语句），
 * 让保留下来的旧库补上新加的列和索引；更大的结构变更仍需 {@code --app.db.keep-existing=false} 重建。
 */
@Configuration
@EnableConfigurationProperties(SqlInitializationProperties.class)
public class SchemaInitConfig {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitConfig.class);

    /** 用来判断"库里是否已有业务数据"的标志表。 */
    static final String MARKER_TABLE = "fruit_item";

    /** 保留旧库时执行的增量结构脚本。 */
    static final String MIGRATE_SCRIPT = "db/migrate-h2.sql";

    @Bean
    public SqlDataSourceScriptDatabaseInitializer dataSourceScriptDatabaseInitializer(
            DataSource dataSource,
            SqlInitializationProperties properties,
            @Value("${app.db.keep-existing:false}") boolean keepExisting) {
        return new SqlDataSourceScriptDatabaseInitializer(dataSource, properties) {
            @Override
            public boolean initializeDatabase() {
                if (keepExisting && tableExists(dataSource, MARKER_TABLE)) {
                    log.info("[db] app.db.keep-existing=true 且库中已有业务表，跳过建表脚本、保留现有数据"
                            + "（如需重置演示数据，启动时加 --app.db.keep-existing=false）");
                    migrate(dataSource);
                    return false;
                }
                if (keepExisting) {
                    log.info("[db] 空库首次启动：执行建表脚本");
                }
                return super.initializeDatabase();
            }
        };
    }

    /** 对保留下来的旧库执行增量结构脚本（每条语句都可重复执行）。 */
    static void migrate(DataSource dataSource) {
        ClassPathResource script = new ClassPathResource(MIGRATE_SCRIPT);
        if (!script.exists()) {
            return;
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(script);
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        DatabasePopulatorUtils.execute(populator, dataSource);
        log.info("[db] 已执行增量结构脚本 {}", MIGRATE_SCRIPT);
    }

    static boolean tableExists(DataSource dataSource, String table) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            for (String name : new String[] {table, table.toUpperCase()}) {
                try (ResultSet rs = meta.getTables(null, null, name, new String[] {"TABLE"})) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            log.warn("[db] 无法判断表 {} 是否存在，按空库处理：{}", table, e.getMessage());
            return false;
        }
    }
}
