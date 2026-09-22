package com.example.migsb.store;

import java.sql.Timestamp;

public final class Rows {
    private Rows() {}

    public record Family(String familyId, String name, String headFingerprint, Timestamp createdAt) {}

    public record Revision(String familyId, String fingerprint, String parentFingerprint,
                           String specJson, Timestamp createdAt) {}

    public record Run(long id, String runUid, String familyId, String fingerprint, String sandboxSchema,
                      String status, String armedFault, String decision, String decisionNote,
                      String terminalEvent, String error, Timestamp createdAt, Timestamp updatedAt) {}

    public record Step(long id, long runId, String stepId, String stepType, int seqNo,
                       String status, String cursorValue, String detail) {}

    public record Journal(long id, String runUid, String stepId, String event,
                          String payload, Timestamp createdAt) {}

    public record Batch(long id, String runUid, String stepId, int batchNo,
                        String pkFrom, String pkTo, int rowCount, String checksum, Timestamp createdAt) {}

    public record ReadCheck(long id, String runUid, String label, String oldPath,
                            String newPath, boolean matched, Timestamp createdAt) {}

    public record InvResult(long id, String runUid, String invariantId,
                            boolean passed, String detail, Timestamp createdAt) {}

    public record WindowWrite(long id, String runUid, String stepId, String pkValue,
                              String payload, String appliedTo, Timestamp createdAt) {}

    public record EvidenceReceipt(long id, String fingerprint, String ruleVersion, String rawJson,
                                  String entryHash, String prevHash, Timestamp receivedAt) {}

    public record EvidenceReport(long id, String sourceFingerprint, String ruleVersion,
                                 boolean passed, String reportJson, Timestamp createdAt) {}
}
