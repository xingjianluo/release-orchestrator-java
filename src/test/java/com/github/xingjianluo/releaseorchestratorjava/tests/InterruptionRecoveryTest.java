package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.Application;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.state.ReleaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 进程在“RUNNING 已落盘、结果未落盘”之间被 kill 后，重启调和的可解释终态与后续回滚。 */
class InterruptionRecoveryTest extends AbstractOrchestratorTest {

    @Autowired
    private ReleaseRepository repository;

    private Release complete(String commit, String content) {
        Release r = orchestrator.submit(new com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest(
                "risk-api", commit, content, null, null));
        for (int i = 0; i < StageName.ordered().size(); i++) {
            r = orchestrator.advance(r.getReleaseId());
        }
        return r;
    }

    private ConfigurableApplicationContext restartContext() {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .properties("spring.task.scheduling.enabled=false")
                .run(
                        "--spring.main.web-application-type=none",
                        "--release.orchestrator.data-dir=" + dataDir().toAbsolutePath(),
                        "--release.orchestrator.recover-on-startup=false");
    }

    @Test
    void runningStageAfterRestartIsReconciledToFailedAndCanRollBack() {
        Release v1 = complete("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "stable-1");

        Release v2 = orchestrator.submit(new com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest(
                "risk-api", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "in-flight-2", null, null));
        v2 = orchestrator.advance(v2.getReleaseId()); // BUILD
        v2 = orchestrator.advance(v2.getReleaseId()); // STAGING
        // 模拟进程在 CANARY 执行中被 kill：RUNNING 已落盘，但阶段结果永远不会写出。
        v2.setStatus(ReleaseStatus.IN_PROGRESS);
        v2.getStages().get(StageName.CANARY).setStatus(StageStatus.RUNNING);
        v2.getStages().get(StageName.CANARY).setStartedAt(Instant.now());
        v2.setUpdatedAt(Instant.now());
        repository.save(v2);
        testLog.info("input simulated crash: release={} stage=CANARY RUNNING persisted, result never written",
                v2.getReleaseId());

        // 全新 Spring 上下文：组件全部新建，状态只能来自磁盘。
        try (ConfigurableApplicationContext restarted = restartContext()) {
            ReleaseOrchestrator recoveredOrchestrator = restarted.getBean(ReleaseOrchestrator.class);

            List<ReleaseOrchestrator.RecoveryResult> results = recoveredOrchestrator.recoverInterrupted();
            assertEquals(1, results.size());
            assertEquals(v2.getReleaseId(), results.get(0).releaseId());
            assertEquals(StageName.CANARY, results.get(0).stage());
            assertEquals(ReleaseStatus.FAILED, results.get(0).newStatus());

            Release reconciled = recoveredOrchestrator.get(v2.getReleaseId());
            assertEquals(ReleaseStatus.FAILED, reconciled.getStatus());
            assertEquals(StageName.CANARY, reconciled.getFailureStage());
            assertEquals("RECOVERED_AFTER_INTERRUPT", reconciled.getFailureCode());
            assertEquals(StageStatus.FAILED, reconciled.getStages().get(StageName.CANARY).getStatus());
            assertEquals(StageStatus.PENDING, reconciled.getStages().get(StageName.PRODUCTION).getStatus());
            testLog.info("decision after restart: FAILED code=RECOVERED_AFTER_INTERRUPT stage=CANARY");

            // 再次恢复必须是空操作（结论稳定、幂等）。
            assertTrue(recoveredOrchestrator.recoverInterrupted().isEmpty());

            // 变更历史跨重启不丢失、不乱序：事件序号严格连续，且崩溃前事件都在。
            com.github.xingjianluo.releaseorchestratorjava.state.EventJournal journal2 =
                    restarted.getBean(com.github.xingjianluo.releaseorchestratorjava.state.EventJournal.class);
            var events = journal2.readAll();
            for (int i = 0; i < events.size(); i++) {
                assertEquals(i + 1, events.get(i).seq(), "event sequence must remain contiguous");
            }
            assertTrue(events.stream().anyMatch(e -> "ARTIFACT_BUILT".equals(e.type())));
            assertTrue(events.stream().anyMatch(e -> "STAGE_STARTED".equals(e.type())
                    && e.detail().contains("stage=CANARY")));
            assertTrue(events.stream().anyMatch(e -> "STAGE_FAILED".equals(e.type())
                    && e.detail().contains("RECOVERED_AFTER_INTERRUPT")));
            testLog.info("decision after restart: journal intact, {} events with contiguous seq", events.size());

            Release rolledBack = recoveredOrchestrator.rollback(v2.getReleaseId());
            assertEquals(ReleaseStatus.ROLLED_BACK, rolledBack.getStatus());
            assertEquals(v1.getVersion(), rolledBack.getRolledBackToVersion());
            testLog.info("final state: recovered failure rolled back to stable version={}",
                    rolledBack.getRolledBackToVersion());
        }
    }

    @Test
    void interruptedRollbackIsReconciledBackToFailedAndRetryable() {
        Release v1 = complete("cccccccccccccccccccccccccccccccccccccccc", "stable-x");
        Release v2 = orchestrator.submit(new com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest(
                "risk-api", "dddddddddddddddddddddddddddddddddddddddd", "broken-x", null, "fail:PRODUCTION"));
        for (int i = 0; i < StageName.ordered().size(); i++) {
            v2 = orchestrator.advance(v2.getReleaseId());
            if (v2.getStatus() == ReleaseStatus.FAILED) {
                break;
            }
        }
        assertEquals(ReleaseStatus.FAILED, v2.getStatus());

        // 模拟回滚过程中被 kill：ROLLING_BACK 已落盘，ROLLBACK_COMPLETED 未写出。
        v2.setStatus(ReleaseStatus.ROLLING_BACK);
        v2.setRolledBackFromVersion(v2.getVersion());
        repository.save(v2);

        try (ConfigurableApplicationContext restarted = restartContext()) {
            ReleaseOrchestrator recoveredOrchestrator = restarted.getBean(ReleaseOrchestrator.class);
            List<ReleaseOrchestrator.RecoveryResult> results = recoveredOrchestrator.recoverInterrupted();
            assertEquals(1, results.size());
            Release state = recoveredOrchestrator.get(v2.getReleaseId());
            assertEquals(ReleaseStatus.FAILED, state.getStatus());
            assertEquals("RECOVERED_AFTER_INTERRUPT", state.getFailureCode());
            testLog.info("decision after restart: interrupted rollback -> FAILED, retryable");

            Release retried = recoveredOrchestrator.rollback(v2.getReleaseId());
            assertEquals(ReleaseStatus.ROLLED_BACK, retried.getStatus());
            assertEquals(v1.getVersion(), retried.getRolledBackToVersion());
            testLog.info("final state: retried rollback completed to={}",
                    retried.getRolledBackToVersion());
        }
    }
}
