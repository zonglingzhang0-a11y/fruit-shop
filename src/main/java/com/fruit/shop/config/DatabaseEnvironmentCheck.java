package com.fruit.shop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * 启动时检查数据库环境里"能启动、但一到卖货就全部报错"的配置，发现就直接停止启动并说清原因。
 *
 * <p>目前只有一项（只对 MySQL / MariaDB）：销售回流的事务固定用"读已提交"（见 SalesService），
 * 而 InnoDB 在读已提交下不能按语句格式写二进制日志——服务器开了 binlog 且 {@code binlog_format=STATEMENT} 时，
 * 每一笔销售回流都会以 ERROR 1665 失败。MySQL 8 默认是 ROW，只有沿用旧运维模板的服务器才会是 STATEMENT。
 */
@Component
public class DatabaseEnvironmentCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DatabaseEnvironmentCheck.class);

    private final DataSource dataSource;

    public DatabaseEnvironmentCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!SchemaInitConfig.isMySql(connection)) {
                return;
            }
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@log_bin, @@binlog_format")) {
                if (!rs.next()) {
                    return;
                }
                boolean binlogOn = rs.getInt(1) == 1;
                String format = rs.getString(2) == null ? "" : rs.getString(2).toUpperCase(Locale.ROOT);
                if (binlogOn && "STATEMENT".equals(format)) {
                    throw new IllegalStateException("MySQL 开启了二进制日志且 binlog_format=STATEMENT："
                            + "销售回流使用'读已提交'事务，InnoDB 在这种组合下拒绝写入（ERROR 1665），每笔销售都会失败。"
                            + "请把服务器的 binlog_format 改为 ROW（MySQL 8 默认）或 MIXED 后再启动。");
                }
                log.info("[db] MySQL 二进制日志 {}，binlog_format={}", binlogOn ? "开启" : "关闭", format);
            }
        }
    }
}
