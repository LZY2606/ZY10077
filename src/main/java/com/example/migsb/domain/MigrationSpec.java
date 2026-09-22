package com.example.migsb.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationSpec(
        String name,
        String description,
        SnapshotDef snapshot,
        List<PreconditionDef> preconditions,
        List<StepDef> steps,
        List<InvariantDef> invariants,
        List<ReadPathDef> readPaths
) {
    public MigrationSpec {
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        invariants = invariants == null ? List.of() : List.copyOf(invariants);
        readPaths = readPaths == null ? List.of() : List.copyOf(readPaths);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotDef(List<TableDef> tables) {
        public SnapshotDef {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableDef(String name, String ddl, List<Map<String, Object>> seed) {
        public TableDef {
            seed = seed == null ? List.of() : List.copyOf(seed);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreconditionDef(String type, String table) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepDef(
            String id,
            String type,
            String description,
            String ddl,
            String probeType,
            String probeTable,
            String source,
            String target,
            String oldTable,
            String newTable,
            String pk,
            Integer batchSize,
            Map<String, String> mapping,
            String where,
            String compensate,
            List<FaultDef> faults
    ) {
        public StepDef {
            mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
            faults = faults == null ? List.of() : List.copyOf(faults);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FaultDef(String id, String label, String phase, String expectVerdict) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvariantDef(
            String id,
            String type,
            String source,
            String target,
            String pk,
            List<String> columns,
            String sqlA,
            String sqlB
    ) {
        public InvariantDef {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReadPathDef(String label, String oldSql, String newSql, List<String> columns) {
        public ReadPathDef {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }
}
