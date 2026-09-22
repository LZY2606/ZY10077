package com.example.migsb.engine;

public record RecoveryVerdict(String runUid, String stepId, String status, String reason) {
    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String COMMITTED = "COMMITTED";
    public static final String WINDOW_OPEN = "WINDOW_OPEN";
    public static final String RESUMABLE = "RESUMABLE";
    public static final String ROLLBACK_RESUMABLE = "ROLLBACK_RESUMABLE";
    public static final String UNCERTAIN = "UNCERTAIN";
    public static final String FINALIZED = "FINALIZED";
}
