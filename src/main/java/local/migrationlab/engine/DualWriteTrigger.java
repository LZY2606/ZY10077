package local.migrationlab.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.h2.api.Trigger;

public class DualWriteTrigger implements Trigger {
    private String targetSchema;
    private String targetTable;
    private int sourceIdIndex;
    private int sourceEmailIndex;
    private int sourceCreatedIndex;

    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) throws SQLException {
        this.targetSchema = schemaName;
        this.targetTable = tableName.equalsIgnoreCase("customers") ? "customers_v2" : "customers";
        int id = 1;
        int email = 2;
        int createdAt = 3;
        try (Statement statement = conn.createStatement();
             ResultSet columns = statement.executeQuery("""
                     select column_name, ordinal_position
                     from information_schema.columns
                     where lower(table_schema) = lower('""" + schemaName + """
                     ') and lower(table_name) = lower('""" + tableName + """
                     ') order by ordinal_position
                     """)) {
            while (columns.next()) {
                String column = columns.getString(1).toLowerCase();
                int ordinal = columns.getInt(2);
                if ("id".equals(column)) {
                    id = ordinal;
                } else if ("email".equals(column)) {
                    email = ordinal;
                } else if ("created_at".equals(column)) {
                    createdAt = ordinal;
                }
            }
        }
        this.sourceIdIndex = id - 1;
        this.sourceEmailIndex = email - 1;
        this.sourceCreatedIndex = createdAt - 1;
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        Object id = newRow[sourceIdIndex];
        Object email = newRow[sourceEmailIndex];
        Object createdAt = newRow[sourceCreatedIndex];
        try (PreparedStatement exists = conn.prepareStatement(
                "select 1 from " + targetSchema + "." + targetTable + " where id = ?")) {
            exists.setObject(1, id);
            if (exists.executeQuery().next()) {
                return;
            }
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "insert into " + targetSchema + "." + targetTable
                        + " (id, email, created_at) values (?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, email);
            insert.setObject(3, createdAt);
            insert.executeUpdate();
        }
    }
}
