package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动恢复：进程可能在“阶段 RUNNING 已落盘、阶段完成未落盘”之间被 kill。
 * 重启时把残留 RUNNING 的阶段调和为 FAILED(RECOVERED_AFTER_INTERRUPT)，
 * 把残留 ROLLING_BACK 调和回 FAILED 等待重试。结论可解释、历史不丢失。
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final ReleaseOrchestrator orchestrator;
    private final OrchestratorProperties properties;

    public StartupRecovery(ReleaseOrchestrator orchestrator, OrchestratorProperties properties) {
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!properties.isRecoverOnStartup()) {
            log.info("startup recovery disabled by configuration");
            return;
        }
        List<ReleaseOrchestrator.RecoveryResult> results = orchestrator.recoverInterrupted();
        for (ReleaseOrchestrator.RecoveryResult result : results) {
            log.warn("recovered interrupted release={} stage={} -> status={}",
                    result.releaseId(), result.stage(), result.newStatus());
        }
    }
}
