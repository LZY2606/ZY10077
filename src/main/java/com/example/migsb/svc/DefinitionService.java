package com.example.migsb.svc;

import com.example.migsb.domain.Fp;
import com.example.migsb.domain.MigrationSpec;
import com.example.migsb.domain.States;
import com.example.migsb.engine.Ident;
import com.example.migsb.store.ControlStore;
import com.example.migsb.store.Rows.Family;
import com.example.migsb.store.Rows.Revision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DefinitionService {

    public static class ConflictException extends RuntimeException {
        public final String familyId;
        public final String expectedHead;
        public final String actualHead;

        public ConflictException(String familyId, String expectedHead, String actualHead) {
            super("定义已被另一方更新（乐观冲突）");
            this.familyId = familyId;
            this.expectedHead = expectedHead;
            this.actualHead = actualHead;
        }
    }

    private final ControlStore store;
    private final ObjectMapper mapper;

    public DefinitionService(ControlStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public MigrationSpec parse(String specJson) {
        try {
            MigrationSpec spec = mapper.readValue(specJson, MigrationSpec.class);
            validate(spec);
            return spec;
        } catch (Exception e) {
            throw new IllegalArgumentException("迁移定义不合法: " + e.getMessage(), e);
        }
    }

    public void validate(MigrationSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("缺少 name");
        }
        if (spec.snapshot() == null || spec.snapshot().tables().isEmpty()) {
            throw new IllegalArgumentException("snapshot.tables 不能为空");
        }
        if (spec.steps().isEmpty()) {
            throw new IllegalArgumentException("steps 不能为空");
        }
        Set<String> tableNames = new HashSet<>();
        for (var t : spec.snapshot().tables()) {
            Ident.check(t.name());
            if (!tableNames.add(t.name())) {
                throw new IllegalArgumentException("快照表名重复: " + t.name());
            }
            if (t.ddl() == null || !t.ddl().toUpperCase().contains("CREATE TABLE")) {
                throw new IllegalArgumentException("表 " + t.name() + " 的 ddl 必须是 CREATE TABLE");
            }
        }
        // DDL 步骤通过探针声明它创建的结构对象，后续步骤可以引用
        for (var st : spec.steps()) {
            if (States.TYPE_DDL.equals(st.type())
                    && States.PROBE_TABLE_EXISTS.equals(st.probeType()) && st.probeTable() != null) {
                tableNames.add(st.probeTable());
            }
        }
        Set<String> stepIds = new HashSet<>();
        Set<String> faultIds = new HashSet<>();
        for (var s : spec.steps()) {
            if (s.id() == null || s.id().isBlank()) {
                throw new IllegalArgumentException("步骤缺少 id");
            }
            if (!stepIds.add(s.id())) {
                throw new IllegalArgumentException("步骤 id 重复: " + s.id());
            }
            switch (s.type() == null ? "" : s.type()) {
                case States.TYPE_DDL -> {
                    if (s.ddl() == null) {
                        throw new IllegalArgumentException("DDL 步骤 " + s.id() + " 缺少 ddl");
                    }
                }
                case States.TYPE_BACKFILL -> {
                    if (!tableNames.contains(s.source())) {
                        throw new IllegalArgumentException("回填步骤 " + s.id() + " 的源表未在快照中定义");
                    }
                    if (s.pk() == null) {
                        throw new IllegalArgumentException("回填步骤 " + s.id() + " 缺少 pk");
                    }
                    if (s.mapping().isEmpty()) {
                        throw new IllegalArgumentException("回填步骤 " + s.id() + " 缺少 mapping");
                    }
                }
                case States.TYPE_DUAL_WRITE -> {
                    if (!tableNames.contains(s.oldTable()) || !tableNames.contains(s.newTable())) {
                        throw new IllegalArgumentException("双写步骤 " + s.id() + " 引用了未定义表");
                    }
                    if (s.pk() == null) {
                        throw new IllegalArgumentException("双写步骤 " + s.id() + " 缺少 pk");
                    }
                    if (s.mapping().isEmpty()) {
                        throw new IllegalArgumentException("双写步骤 " + s.id() + " 缺少 mapping");
                    }
                }
                case States.TYPE_SIDE_EFFECT -> {
                }
                default -> throw new IllegalArgumentException("步骤 " + s.id() + " 类型未知: " + s.type());
            }
            for (var f : s.faults()) {
                if (f.id() == null || f.phase() == null) {
                    throw new IllegalArgumentException("步骤 " + s.id() + " 的故障点缺少 id/phase");
                }
                if (!faultIds.add(s.id() + "#" + f.id())) {
                    throw new IllegalArgumentException("故障点 id 重复: " + s.id() + "#" + f.id());
                }
            }
        }
        for (var p : spec.preconditions()) {
            if (!"TABLE_EXISTS".equals(p.type()) || !tableNames.contains(p.table())) {
                throw new IllegalArgumentException("不支持的前置条件或表不存在: " + p.table());
            }
        }
        for (var inv : spec.invariants()) {
            if (inv.id() == null) {
                throw new IllegalArgumentException("不变量缺少 id");
            }
            switch (inv.type() == null ? "" : inv.type()) {
                case "ROW_COUNT_EQUAL", "ORDERED_CHECKSUM_EQUAL" -> {
                    if (!tableNames.contains(inv.source()) || !tableNames.contains(inv.target())) {
                        throw new IllegalArgumentException("不变量 " + inv.id() + " 引用了未定义表");
                    }
                }
                case "QUERY_EQUAL" -> {
                    if (inv.sqlA() == null || inv.sqlB() == null) {
                        throw new IllegalArgumentException("不变量 " + inv.id() + " 缺少 sqlA/sqlB");
                    }
                }
                default -> throw new IllegalArgumentException("不变量 " + inv.id() + " 类型未知: " + inv.type());
            }
        }
        for (var rp : spec.readPaths()) {
            if (rp.label() == null || rp.oldSql() == null || rp.newSql() == null) {
                throw new IllegalArgumentException("读路径缺少 label/oldSql/newSql");
            }
        }
    }

    private void requireRefs(com.example.migsb.domain.MigrationSpec.StepDef s, Set<String> tables, boolean backfill) {
        if (!backfill && (!tables.contains(s.oldTable()) || !tables.contains(s.newTable()))) {
            throw new IllegalArgumentException("双写步骤 " + s.id() + " 引用了未定义表");
        }
    }

    public String fingerprintOf(String specJson) {
        try {
            JsonNode tree = mapper.readTree(specJson);
            return Fp.specFingerprint(mapper, tree);
        } catch (Exception e) {
            throw new IllegalArgumentException("定义 JSON 无法解析: " + e.getMessage(), e);
        }
    }

    public MigrationSpec parseRevision(Revision revision) {
        return parse(revision.specJson());
    }

    /**
     * 新建或按家族提交新版本。baseFingerprint 为浏览器所持旧版本；与家族 head 不一致即冲突。
     */
    public record SubmitResult(String familyId, String fingerprint, String parentFingerprint,
                               boolean created, boolean branched) {}

    public SubmitResult submit(String rawJson, String familyId, String baseFingerprint, boolean forceBranch) {
        String fingerprint = fingerprintOf(rawJson);
        MigrationSpec spec = parse(rawJson);
        if (store.findRevision(fingerprint).isPresent()) {
            Revision existing = store.findRevision(fingerprint).get();
            return new SubmitResult(existing.familyId(), fingerprint, existing.parentFingerprint(), false, false);
        }
        if (familyId != null) {
            Family family = store.findFamily(familyId)
                    .orElseThrow(() -> new IllegalArgumentException("家族不存在: " + familyId));
            boolean branch = forceBranch;
            if (!branch && baseFingerprint != null && !baseFingerprint.equals(family.headFingerprint())) {
                throw new ConflictException(familyId, baseFingerprint, family.headFingerprint());
            }
            if (baseFingerprint == null && !forceBranch) {
                // 未提供乐观锁基版本时，默认作为分支保存，避免隐式移动 head
                branch = true;
            }
            store.insertRevision(familyId, fingerprint,
                    baseFingerprint != null ? baseFingerprint : family.headFingerprint(), rawJson);
            if (!branch) {
                store.updateFamilyHead(familyId, fingerprint);
            }
            return new SubmitResult(familyId, fingerprint, baseFingerprint, false, branch);
        }
        String newFamilyId = Fp.randomId(6);
        store.insertFamily(newFamilyId, spec.name(), fingerprint);
        store.insertRevision(newFamilyId, fingerprint, null, rawJson);
        return new SubmitResult(newFamilyId, fingerprint, null, true, false);
    }

    public List<Revision> history(String familyId) {
        return store.listRevisions(familyId);
    }

    public Optional<Revision> head(String familyId) {
        return store.findFamily(familyId).flatMap(f -> store.findRevision(f.headFingerprint()));
    }

    public Map<String, Object> revisionView(Revision r) {
        Family f = store.findFamily(r.familyId()).orElse(null);
        return Map.of(
                "familyId", r.familyId(),
                "fingerprint", r.fingerprint(),
                "parentFingerprint", r.parentFingerprint() == null ? "" : r.parentFingerprint(),
                "isHead", f != null && r.fingerprint().equals(f.headFingerprint()),
                "createdAt", r.createdAt().toInstant().toString(),
                "spec", parse(r.specJson())
        );
    }
}
