package local.migrationlab.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import local.migrationlab.support.ApiException;
import local.migrationlab.support.Identifiers;
import local.migrationlab.support.Json;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DefinitionStore {
    private final JdbcTemplate jdbc;

    public DefinitionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> saveBranch(String definitionId, String parentFingerprint, String rawContent) {
        JsonNode definition = validate(rawContent);
        String requestedId = Identifiers.text(definition, "id");
        if (!requestedId.equals(definitionId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Definition id in JSON does not match the URL");
        }
        Map<String, Object> parent = findByFingerprint(parentFingerprint);
        if (parent == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Parent fingerprint is unknown; reload the latest branch first");
        }
        int parentVersion = ((Number) parent.get("version")).intValue();
        if (!parent.get("fingerprint").equals(parentFingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "Stale parent fingerprint");
        }
        String fingerprint = Json.sha256(Json.canonical(definition));
        Map<String, Object> existing = findByFingerprint(fingerprint);
        if (existing != null) {
            return existing;
        }
        Integer maxVersion = jdbc.queryForObject(
                "select max(version) from control.definition_version where definition_id = ?",
                Integer.class, definitionId);
        int version = (maxVersion == null ? 0 : maxVersion) + 1;
        if (parentVersion < (maxVersion == null ? 0 : maxVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "A newer branch already exists. Reload and merge it.");
        }
        jdbc.update("""
                insert into control.definition_version
                (definition_id, version, fingerprint, parent_fingerprint, title, content, rule_version)
                values (?, ?, ?, ?, ?, ?, ?)
                """, definitionId, version, fingerprint, parentFingerprint,
                Identifiers.text(definition, "title"), Json.write(definition), Json.RULE_VERSION);
        return view(find(definitionId, version));
    }

    public Map<String, Object> saveInitial(String rawContent) {
        JsonNode definition = validate(rawContent);
        String id = Identifiers.text(definition, "id");
        String fingerprint = Json.sha256(Json.canonical(definition));
        Map<String, Object> existing = findByFingerprint(fingerprint);
        if (existing != null) {
            return existing;
        }
        Integer version = jdbc.queryForObject(
                "select coalesce(max(version), 0) + 1 from control.definition_version where definition_id = ?",
                Integer.class, id);
        jdbc.update("""
                insert into control.definition_version
                (definition_id, version, fingerprint, parent_fingerprint, title, content, rule_version)
                values (?, ?, ?, null, ?, ?, ?)
                """, id, version, fingerprint, Identifiers.text(definition, "title"),
                Json.write(definition), Json.RULE_VERSION);
        return view(find(id, version));
    }

    public Map<String, Object> latest(String definitionId) {
        Integer version = jdbc.queryForObject(
                "select max(version) from control.definition_version where definition_id = ?",
                Integer.class, definitionId);
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Definition not found");
        }
        return view(find(definitionId, version));
    }

    public Map<String, Object> byFingerprint(String fingerprint) {
        Map<String, Object> result = findByFingerprint(fingerprint);
        if (result == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Definition fingerprint not found");
        }
        return view(result);
    }

    public List<Map<String, Object>> versions(String definitionId) {
        return jdbc.query("""
                select definition_id, version, fingerprint, parent_fingerprint, title, content,
                       rule_version, created_at
                from control.definition_version
                where definition_id = ? order by version
                """, (rs, row) -> {
            Map<String, Object> item = baseRow(rs);
            item.put("content", Json.read(rs.getString("content")));
            return item;
        }, definitionId);
    }

    public List<Map<String, Object>> definitions() {
        return jdbc.query("""
                select definition_id, version, fingerprint, parent_fingerprint, title, content,
                       rule_version, created_at
                from definition_version v
                where version = (select max(version) from definition_version x
                                 where x.definition_id = v.definition_id)
                order by definition_id
                """.replace(" definition_version", " control.definition_version")
                .replace("control.definition_version v", "control.definition_version v"),
                (rs, row) -> baseRow(rs));
    }

    private Map<String, Object> findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.query(
                "select * from control.definition_version where fingerprint = ?",
                (rs, row) -> baseRow(rs), fingerprint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> find(String id, int version) {
        List<Map<String, Object>> rows = jdbc.query(
                "select * from control.definition_version where definition_id = ? and version = ?",
                (rs, row) -> baseRow(rs), id, version);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Definition version not found");
        }
        return rows.get(0);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        row.put("content", Json.read((String) row.get("content")));
        return row;
    }

    private Map<String, Object> baseRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("definitionId", rs.getString("definition_id"));
        row.put("version", rs.getInt("version"));
        row.put("fingerprint", rs.getString("fingerprint"));
        row.put("parentFingerprint", rs.getString("parent_fingerprint"));
        row.put("title", rs.getString("title"));
        row.put("content", rs.getString("content"));
        row.put("ruleVersion", rs.getString("rule_version"));
        Timestamp timestamp = rs.getTimestamp("created_at");
        row.put("createdAt", timestamp == null ? null : timestamp.toInstant());
        return row;
    }

    private JsonNode validate(String rawContent) {
        JsonNode node;
        try {
            node = Json.read(rawContent);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        for (String field : List.of("id", "title", "schema", "snapshot", "steps", "invariants")) {
            if (!node.has(field) || node.get(field).isNull()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Definition must contain " + field);
            }
        }
        if (!node.get("steps").isArray() || node.get("steps").isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Definition steps must be a non-empty array");
        }
        for (JsonNode step : node.get("steps")) {
            Identifiers.text(step, "id");
            Identifiers.text(step, "type");
        }
        return node;
    }
}
