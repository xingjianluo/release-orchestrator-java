package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.group.release.ReleaseGroupOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动恢复，按顺序调和两层：
 * <ol>
 *   <li>单发布层：残留 RUNNING 阶段 → FAILED(RECOVERED_AFTER_INTERRUPT)，
 *       残留 ROLLING_BACK → FAILED 等待重试；发布组的成员也是普通发布，一并覆盖；</li>
 *   <li>发布组层：补齐中断窗口中缺失的成员，用成员权威状态刷新组快照。</li>
 * </ol>
 * 结论可解释、历史不丢失；两层恢复本身都幂等。
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final ReleaseOrchestrator orchestrator;
    private final ReleaseGroupOrchestrator groupOrchestrator;
    private final OrchestratorProperties properties;

    public StartupRecovery(ReleaseOrchestrator orchestrator,
                           ReleaseGroupOrchestrator groupOrchestrator,
                           OrchestratorProperties properties) {
        this.orchestrator = orchestrator;
        this.groupOrchestrator = groupOrchestrator;
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
        int groups = groupOrchestrator.recoverInterrupted();
        if (groups > 0) {
            log.warn("recovered {} interrupted release group(s)", groups);
        }
    }
}
