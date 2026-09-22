package com.example.migsb.engine;

import com.example.migsb.domain.MigrationSpec.TableDef;
import com.example.migsb.domain.Fp;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SandboxService {

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public SandboxService(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    public void createSchema(String schema) {
        jdbc().execute("CREATE SCHEMA IF NOT EXISTS \"" + Ident.schema(schema) + "\"");
    }

    public void dropSchema(String schema) {
        jdbc().execute("DROP SCHEMA IF EXISTS \"" + Ident.schema(schema) + "\" CASCADE");
    }

    public void buildSnapshot(String schema, List<TableDef> tables) {
        JdbcTemplate j = jdbc();
        for (TableDef table : tables) {
            Ident.check(table.name());
            execDdlInSchema(schema, table.ddl());
            for (Map<String, Object> row : table.seed()) {
                insertSeed(schema, table.name(), row);
            }
        }
    }

    public void insertSeed(String schema, String table, Map<String, Object> row) {
        List<String> cols = new ArrayList<>(row.keySet());
        cols.forEach(Ident::check);
        String sql = "INSERT INTO " + Ident.q(schema, table) + " ("
                + String.join(",", cols.stream().map(Ident::qColumn).toList()) + ") VALUES ("
                + String.join(",", cols.stream().map(c -> "?").toList()) + ")";
        jdbc().update(sql, cols.stream().map(row::get).toArray());
    }

    public void execDdlInSchema(String schema, String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return;
        }
        Connection raw = null;
        try {
            raw = dataSource.getConnection();
            try (Connection conn = raw) {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET SCHEMA \"" + Ident.schema(schema) + "\"");
                    for (String piece : splitStatements(ddl)) {
                        st.execute(piece);
                    }
                    st.execute("SET SCHEMA PUBLIC");
                }
            }
        } catch (SQLException e) {
            try {
                if (raw != null && !raw.isClosed()) {
                    try (Statement st = raw.createStatement()) {
                        st.execute("SET SCHEMA PUBLIC");
                    }
                }
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("DDL 执行失败: " + e.getMessage(), e);
        }
    }

    private List<String> splitStatements(String ddl) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            if (c == '\'') {
                quote = !quote;
                cur.append(c);
            } else if (c == ';' && !quote) {
                if (!cur.toString().isBlank()) {
                    out.add(cur.toString().trim());
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    public boolean tableExists(String schema, String table) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                Integer.class, schema, table.toLowerCase());
        return n != null && n > 0;
    }

    public List<String> columns(String schema, String table) {
        return jdbc().queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? "
                        + "ORDER BY ORDINAL_POSITION",
                String.class, schema, table.toLowerCase());
    }

    public long countRows(String schema, String table, String where) {
        String sql = "SELECT COUNT(*) FROM " + Ident.q(schema, table)
                + (where != null && !where.isBlank() ? " WHERE " + where : "");
        Long n = jdbc().queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> rowsAfter(String schema, String table, String pk,
                                               String cursor, int limit, String where) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(Ident.q(schema, table))
                .append(" WHERE ").append(Ident.qColumn(pk)).append(" > ?");
        List<Object> args = new ArrayList<>(List.of(compareValue(cursor)));
        if (where != null && !where.isBlank()) {
            sql.append(" AND (").append(where).append(")");
        }
        sql.append(" ORDER BY ").append(Ident.qColumn(pk)).append(" LIMIT ").append(limit);
        return jdbc().queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> allRows(String schema, String table, String pk, String where) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(Ident.q(schema, table));
        List<Object> args = new ArrayList<>();
        if (where != null && !where.isBlank()) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" ORDER BY ").append(Ident.qColumn(pk));
        return jdbc().queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> rowByPk(String schema, String table, String pk, Object value) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT * FROM " + Ident.q(schema, table) + " WHERE " + Ident.qColumn(pk) + "=?",
                compareValue(String.valueOf(value)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteTargetAfter(String schema, String target, String pk, String cursor) {
        jdbc().update("DELETE FROM " + Ident.q(schema, target) + " WHERE " + Ident.qColumn(pk) + " > ?",
                compareValue(cursor));
    }

    public void deleteTargetAll(String schema, String target) {
        jdbc().update("DELETE FROM " + Ident.q(schema, target));
    }

    /**
     * 以 MERGE 写入一批行。mapping: source 列 -> target 列；pk 为 source 主键列，目标主键列同名。
     */
    public int mergeBatch(String schema, String target, String pk, Map<String, String> mapping,
                          List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> actualColumns = columns(schema, target);
        List<String> targetCols = new ArrayList<>();
        targetCols.add(resolveName(actualColumns, pk));
        for (String v : mapping.values()) {
            targetCols.add(resolveName(actualColumns, v));
        }
        String sql = "MERGE INTO " + Ident.q(schema, target) + " ("
                + String.join(",", targetCols.stream().map(Ident::qColumn).toList()) + ") KEY("
                + Ident.qColumn(pk) + ") VALUES ("
                + String.join(",", targetCols.stream().map(c -> "?").toList()) + ")";
        List<Object[]> args = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] vals = new Object[targetCols.size()];
            vals[0] = compareValue(String.valueOf(row.get(pk)));
            int i = 1;
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                vals[i++] = row.get(e.getKey());
            }
            args.add(vals);
        }
        jdbc().batchUpdate(sql, args);
        return rows.size();
    }

    public void mergeMapped(String schema, String source, String target, String pk,
                            Map<String, String> mapping) {
        List<Map<String, Object>> rows = allRows(schema, source, pk, null);
        if (rows.isEmpty()) {
            return;
        }
        List<String> actualColumns = columns(schema, target);
        List<String> targetCols = new ArrayList<>();
        targetCols.add(resolveName(actualColumns, pk));
        for (String v : mapping.values()) {
            targetCols.add(resolveName(actualColumns, v));
        }
        String sql = "MERGE INTO " + Ident.q(schema, target) + " ("
                + String.join(",", targetCols.stream().map(Ident::qColumn).toList()) + ") KEY("
                + Ident.qColumn(targetCols.get(0)) + ") VALUES ("
                + String.join(",", targetCols.stream().map(c -> "?").toList()) + ")";
        List<Object[]> args = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] vals = new Object[targetCols.size()];
            vals[0] = compareValue(String.valueOf(getValue(row, pk)));
            int i = 1;
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                vals[i++] = getValue(row, e.getKey());
            }
            args.add(vals);
        }
        jdbc().batchUpdate(sql, args);
    }

    private Object getValue(Map<String, Object> row, String name) {
        if (row.containsKey(name)) {
            return row.get(name);
        }
        return row.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst()
                .map(row::get).orElseThrow(() -> new IllegalStateException("源列不存在: " + name));
    }

    public void mergeMappedRow(String schema, com.example.migsb.domain.MigrationSpec.StepDef def,
                               Map<String, Object> sourceRow, boolean old, boolean target) {
        if (old) {
            List<String> oldCols = new ArrayList<>();
            oldCols.add(def.pk());
            oldCols.addAll(def.mapping().keySet());
            mergeOneRow(schema, def.oldTable(), def.pk(), oldCols, def.mapping(), sourceRow, true);
        }
        if (target) {
            List<String> newCols = new ArrayList<>();
            newCols.add(def.pk());
            newCols.addAll(def.mapping().values());
            mergeOneRow(schema, def.newTable(), def.pk(), newCols, def.mapping(), sourceRow, false);
        }
    }

    private void mergeOneRow(String schema, String table, String pk, List<String> requestedCols,
                             Map<String, String> mapping, Map<String, Object> sourceRow, boolean oldSide) {
        List<String> actualColumns = columns(schema, table);
        List<String> cols = requestedCols.stream().map(c -> resolveName(actualColumns, c)).toList();
        String sql = "MERGE INTO " + Ident.q(schema, table) + " ("
                + String.join(",", cols.stream().map(Ident::qColumn).toList()) + ") KEY("
                + Ident.qColumn(cols.get(0)) + ") VALUES ("
                + String.join(",", cols.stream().map(c -> "?").toList()) + ")";
        Object[] vals = new Object[cols.size()];
        vals[0] = compareValue(String.valueOf(getValue(sourceRow, pk)));
        // 新侧列名经过映射，需要反向取源列值；旧侧列名即源列名
        java.util.Map<String, String> inverse = new java.util.HashMap<>();
        mapping.forEach((srcCol, tgtCol) -> inverse.put(tgtCol, srcCol));
        for (int i = 1; i < requestedCols.size(); i++) {
            String requested = requestedCols.get(i);
            String sourceKey = oldSide ? requested : inverse.getOrDefault(requested, requested);
            vals[i] = getValue(sourceRow, sourceKey);
        }
        jdbc().update(sql, vals);
    }

    public void insertRow(String schema, String table, Map<String, Object> row) {
        List<String> cols = new ArrayList<>(row.keySet());
        String sql = "INSERT INTO " + Ident.q(schema, table) + " ("
                + String.join(",", cols.stream().map(Ident::qColumn).toList()) + ") VALUES ("
                + String.join(",", cols.stream().map(c -> "?").toList()) + ")";
        jdbc().update(sql, cols.stream().map(c -> {
            Object v = row.get(c);
            return c.equals(cols.get(0)) ? compareValue(String.valueOf(v)) : v;
        }).toArray());
    }

    public Object scalar(String schema, String sql) {
        String withSchema = sql.replace(":schema", "\"" + Ident.schema(schema) + "\"");
        return jdbc().queryForObject(withSchema, Object.class);
    }

    public List<Map<String, Object>> query(String schema, String sql) {
        String withSchema = sql.replace(":schema", "\"" + Ident.schema(schema) + "\"");
        return jdbc().queryForList(withSchema);
    }

    /**
     * 按主键排序的整表行摘要：sha256(规范化拼接)，用于旧/新路径一致性与回填终态判定。
     */
    public String orderedChecksum(String schema, String table, String pk, List<String> columns) {
        List<Map<String, Object>> rows = allRows(schema, table, pk, null);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> row : rows) {
                Map<String, Object> view = new TreeMap<>();
                List<String> useCols = columns == null || columns.isEmpty()
                        ? new ArrayList<>(row.keySet()) : resolveCols(row, columns);
                for (String c : useCols) {
                    view.put(c, normalize(row.get(c)));
                }
                md.update(mapper.writeValueAsBytes(view));
                md.update((byte) '\n');
            }
            md.update(("rows=" + rows.size()).getBytes(StandardCharsets.UTF_8));
            return Fp.sha256Hex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("校验摘要计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 新表数据按 target->source 规范回命名后做摘要，使新旧路径基于同一逻辑列名比较。
     */
    public String mappedChecksum(String schema, String table, String pk, Map<String, String> mapping) {
        List<Map<String, Object>> rows = allRows(schema, table, pk, null);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> row : rows) {
                Map<String, Object> view = new TreeMap<>();
                view.put(pk, normalize(getValue(row, pk)));
                mapping.forEach((srcCol, tgtCol) -> view.put(srcCol, normalize(getValue(row, tgtCol))));
                md.update(mapper.writeValueAsBytes(view));
                md.update((byte) '\n');
            }
            md.update(("rows=" + rows.size()).getBytes(StandardCharsets.UTF_8));
            return Fp.sha256Hex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("映射摘要计算失败: " + e.getMessage(), e);
        }
    }

    public String checksumOfRows(List<Map<String, Object>> rows, List<String> columns) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> row : rows) {
                Map<String, Object> view = new TreeMap<>();
                List<String> useCols = columns == null || columns.isEmpty()
                        ? new ArrayList<>(row.keySet()) : resolveCols(row, columns);
                for (String c : useCols) {
                    view.put(c, normalize(row.get(c)));
                }
                md.update(mapper.writeValueAsBytes(view));
                md.update((byte) '\n');
            }
            md.update(("rows=" + rows.size()).getBytes(StandardCharsets.UTF_8));
            return Fp.sha256Hex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("批次摘要计算失败: " + e.getMessage(), e);
        }
    }

    public String rowsJson(List<Map<String, Object>> rows) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object normalize(Object value) {
        if (value instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return value;
    }

    private List<String> resolveCols(Map<String, Object> row, List<String> requested) {
        List<String> out = new ArrayList<>();
        for (String c : requested) {
            if (row.containsKey(c)) {
                out.add(c);
                continue;
            }
            String found = row.keySet().stream()
                    .filter(k -> k.equalsIgnoreCase(c)).findFirst().orElse(null);
            if (found == null) {
                throw new IllegalStateException("列在结果集中不存在: " + c + "，可用列=" + row.keySet());
            }
            out.add(found);
        }
        return out;
    }

    public String nextPk(String schema, String table, String pk) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT MAX(" + Ident.qColumn(pk) + ") AS M FROM " + Ident.q(schema, table));
        Object m = rows.get(0).get("M");
        if (m == null) {
            return "1";
        }
        return String.valueOf(Long.parseLong(String.valueOf(m)) + 1);
    }

    private String resolveName(List<String> actual, String requested) {
        return actual.stream().filter(c -> c.equalsIgnoreCase(requested)).findFirst()
                .orElseThrow(() -> new IllegalStateException("列不存在: " + requested + "，可用列=" + actual));
    }

    private Object compareValue(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    public Map<String, Object> jsonRow(String json) {
        try {
            return new HashMap<>(mapper.readValue(json, Map.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("行数据不是合法 JSON: " + e.getMessage());
        }
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
