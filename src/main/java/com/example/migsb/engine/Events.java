package com.example.migsb.engine;

public final class Events {
    private Events() {}

    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String STEP_STARTED = "STEP_STARTED";
    public static final String DDL_APPLIED = "DDL_APPLIED";
    public static final String STEP_COMMITTED = "STEP_COMMITTED";
    public static final String STEP_SKIPPED_COMMITTED = "STEP_SKIPPED_COMMITTED";
    public static final String BACKFILL_BATCH_COMMITTED = "BACKFILL_BATCH_COMMITTED";
    public static final String BACKFILL_RESUME_TRUNCATED = "BACKFILL_RESUME_TRUNCATED";
    public static final String WINDOW_OPENED = "WINDOW_OPENED";
    public static final String WINDOW_WRITE = "WINDOW_WRITE";
    public static final String WINDOW_RECONCILED = "WINDOW_RECONCILED";
    public static final String WINDOW_CLOSED = "WINDOW_CLOSED";
    public static final String SIDE_EFFECT_APPLIED = "SIDE_EFFECT_APPLIED";
    public static final String READ_CHECK = "READ_CHECK";
    public static final String INVARIANT_CHECK = "INVARIANT_CHECK";
    public static final String RUN_COMPLETED = "RUN_COMPLETED";
    public static final String RUN_HALTED = "RUN_HALTED";
    public static final String ROLLBACK_STARTED = "ROLLBACK_STARTED";
    public static final String STEP_ROLLED_BACK = "STEP_ROLLED_BACK";
    public static final String RUN_ROLLED_BACK = "RUN_ROLLED_BACK";
    public static final String HUMAN_DECISION = "HUMAN_DECISION";
    public static final String RECOVERY_VERDICT = "RECOVERY_VERDICT";
}
