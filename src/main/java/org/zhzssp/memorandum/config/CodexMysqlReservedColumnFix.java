package org.zhzssp.memorandum.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 赶在 Hibernate hbm2ddl 之前，把旧库 {@code kb_path_snapshot.cursor} 改名为
 * {@code path_cursor}。MySQL 8 把 CURSOR 当保留字，未加反引号的建表语句会失败。
 */
@Component
public class CodexMysqlReservedColumnFix implements BeanPostProcessor, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(CodexMysqlReservedColumnFix.class);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource ds) {
            renameIfNeeded(ds);
        }
        return bean;
    }

    private void renameIfNeeded(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            if (!hasColumn(c, "kb_path_snapshot", "cursor")) {
                return;
            }
            if (hasColumn(c, "kb_path_snapshot", "path_cursor")) {
                return;
            }
            s.execute("ALTER TABLE kb_path_snapshot CHANGE COLUMN `cursor` path_cursor VARCHAR(64) NULL");
            log.info("[Codex] renamed kb_path_snapshot.cursor to path_cursor");
        } catch (Exception e) {
            log.warn("[Codex] skip cursor column rename: {}", e.getMessage());
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws Exception {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, column)) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }
}
