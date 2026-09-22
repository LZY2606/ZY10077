package com.example.migsb.engine;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("crashtest")
public class TestCrashSimulator implements CrashSimulator {

    @Override
    public void trigger(String runUid, String stepId, String faultId, String phase) {
        throw new SimulatedCrashException("故障点 " + stepId + "#" + faultId
                + " (" + phase + ") 触发，进程已退出（测试替身） run=" + runUid);
    }
}
