package com.example.migsb.config;

import com.example.migsb.engine.RecoveryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final RecoveryScanner scanner;

    public StartupRecovery(RecoveryScanner scanner) {
        this.scanner = scanner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        RecoveryScanner.ScanReport report = scanner.scanAll(true);
        for (var v : report.verdicts()) {
            log.info("恢复判定 run={} step={} verdict={} reason={}",
                    v.runUid(), v.stepId(), v.status(), v.reason());
        }
        for (String uid : report.halted()) {
            log.warn("run={} 已置为 HALTED，等待人工在页面裁定，禁止自动重跑", uid);
        }
    }
}
