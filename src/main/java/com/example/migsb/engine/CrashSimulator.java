package com.example.migsb.engine;

public interface CrashSimulator {
    /**
     * 在声明的故障点模拟进程退出。WAL 必须已在调用前持久化。
     */
    void trigger(String runUid, String stepId, String faultId, String phase);
}
