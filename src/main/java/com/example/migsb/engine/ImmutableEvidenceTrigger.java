package com.example.migsb.engine;

import java.sql.Connection;
import java.sql.SQLException;
import org.h2.api.Trigger;

/**
 * 原始证据只追加：拒绝 UPDATE 与 DELETE。
 */
public class ImmutableEvidenceTrigger implements Trigger {

    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) {
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("原始证据为只追加记录，禁止改写或删除");
    }

    @Override
    public void close() {
    }

    @Override
    public void remove() {
    }
}
