package local.migrationlab.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PreconditionChecker {
    private final PhysicalMigrator physical;

    public PreconditionChecker(PhysicalMigrator physical) {
        this.physical = physical;
    }

    public CheckResult checkAll(Connection connection, String schema, JsonNode checks) throws SQLException {
        List<CheckResult> results = new ArrayList<>();
        if (checks != null && checks.isArray()) {
            for (JsonNode check : checks) {
                results.add(check(connection, schema, check));
            }
        }
        return CheckResult.all(results);
    }

    public CheckResult check(Connection connection, String schema, JsonNode check) throws SQLException {
        String type = check.path("type").asText();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("definition", detailNode(check));
        return switch (type) {
            case "tableExists" -> {
                String table = check.path("table").asText();
                boolean exists = physical.tableExists(connection, schema, table);
                detail.put("observed", exists);
                yield exists ? CheckResult.ok("表 " + table + " 存在", detail)
                        : CheckResult.fail("缺少表 " + table, detail);
            }
            case "tableMissing" -> {
                String table = check.path("table").asText();
                boolean exists = physical.tableExists(connection, schema, table);
                detail.put("observed", exists);
                yield !exists ? CheckResult.ok("表 " + table + " 尚不存在", detail)
                        : CheckResult.fail("表 " + table + " 已存在", detail);
            }
            case "triggerExists" -> {
                String name = check.path("name").asText();
                boolean exists = physical.triggerExists(connection, schema, name);
                detail.put("observed", exists);
                yield exists ? CheckResult.ok("触发器 " + name + " 存在", detail)
                        : CheckResult.fail("缺少触发器 " + name, detail);
            }
            case "triggerMissing" -> {
                String name = check.path("name").asText();
                boolean exists = physical.triggerExists(connection, schema, name);
                detail.put("observed", exists);
                yield !exists ? CheckResult.ok("触发器 " + name + " 不存在", detail)
                        : CheckResult.fail("触发器 " + name + " 已存在", detail);
            }
            default -> CheckResult.fail("未知检查类型: " + type, detail);
        };
    }

    public CheckResult verifyData(Connection connection, String schema, boolean afterCutover) throws SQLException {
        Map<String, Object> detail = new LinkedHashMap<>();
        String oldTable = afterCutover ? "customers_old" : "customers";
        String newTable = afterCutover ? "customers" : "customers_v2";
        boolean oldExists = physical.tableExists(connection, schema, oldTable);
        boolean newExists = physical.tableExists(connection, schema, newTable);
        detail.put("oldPathTable", oldTable);
        detail.put("newPathTable", newTable);
        detail.put("oldPathExists", oldExists);
        detail.put("newPathExists", newExists);
        if (!oldExists || !newExists) {
            return CheckResult.fail("旧读路径或新读路径缺少物理表", detail);
        }
        long oldCount = physical.count(connection, schema, oldTable);
        long newCount = physical.count(connection, schema, newTable);
        detail.put("oldCount", oldCount);
        detail.put("newCount", newCount);
        if (oldCount != newCount) {
            return CheckResult.fail("旧读路径 " + oldCount + " 行，新读路径 " + newCount + " 行", detail);
        }
        List<List<Object>> oldRows = physical.readPath(connection, schema, oldTable);
        List<List<Object>> newRows = physical.readPath(connection, schema, newTable);
        detail.put("oldChecksum", local.migrationlab.support.Json.sha256(oldRows.toString()));
        detail.put("newChecksum", local.migrationlab.support.Json.sha256(newRows.toString()));
        detail.put("readPathsConsistent", oldRows.equals(newRows));
        if (!oldRows.equals(newRows)) {
            int firstDifference = 0;
            while (firstDifference < Math.min(oldRows.size(), newRows.size())
                    && oldRows.get(firstDifference).equals(newRows.get(firstDifference))) {
                firstDifference++;
            }
            detail.put("firstDifference", firstDifference);
            return CheckResult.fail("旧读路径和新读路径在行 " + firstDifference + " 后不一致", detail);
        }
        long normalizedMismatches = 0;
        if (!afterCutover) {
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("""
                         select count(*) from %s.customers_v2
                         where email_normalized <> lower(trim(email))
                         """.formatted(schema))) {
                rs.next();
                normalizedMismatches = rs.getLong(1);
            }
        }
        detail.put("normalizedMismatches", normalizedMismatches);
        if (normalizedMismatches != 0) {
            return CheckResult.fail("规范化邮箱派生列存在不匹配", detail);
        }
        return CheckResult.ok("旧读路径与新读路径逐行一致，共 " + oldCount + " 行", detail);
    }

    private Map<String, Object> detailNode(JsonNode node) {
        return local.migrationlab.support.Json.mapper().convertValue(node,
                local.migrationlab.support.Json.mapper().getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }
}
