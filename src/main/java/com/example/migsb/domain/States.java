package com.example.migsb.domain;

public final class States {
    private States() {}

    public static final String RUN_CREATED = "CREATED";
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_COMMITTED = "COMMITTED";
    public static final String RUN_HALTED = "HALTED";
    public static final String RUN_ROLLING_BACK = "ROLLING_BACK";
    public static final String RUN_ROLLED_BACK = "ROLLED_BACK";
    public static final String RUN_FAILED = "FAILED";

    public static final String STEP_PENDING = "PENDING";
    public static final String STEP_COMMITTED = "COMMITTED";
    public static final String STEP_WINDOW_OPEN = "WINDOW_OPEN";
    public static final String STEP_HALTED = "HALTED";
    public static final String STEP_ROLLED_BACK = "ROLLED_BACK";

    public static final String DECISION_COMMIT = "COMMIT";
    public static final String DECISION_COMPENSATE = "COMPENSATE";

    public static final String TYPE_DDL = "DDL";
    public static final String TYPE_BACKFILL = "BACKFILL";
    public static final String TYPE_DUAL_WRITE = "DUAL_WRITE";
    public static final String TYPE_SIDE_EFFECT = "SIDE_EFFECT";

    public static final String PROBE_TABLE_EXISTS = "TABLE_EXISTS";
    public static final String PROBE_NONE = "NONE";

    public static final String APPLY_OLD = "OLD";
    public static final String APPLY_NEW = "NEW";
    public static final String APPLY_BOTH = "BOTH";
}
