package com.example.migsb.engine;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!crashtest")
public class ProductionCrashSimulator implements CrashSimulator {

    @Override
    public void trigger(String runUid, String stepId, String faultId, String phase) {
        // halt 而非 exit：跳过 shutdown hook，最接近进程被杀死；WAL 已先行落盘
        Runtime.getRuntime().halt(3);
    }
}
