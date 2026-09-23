package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseState;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStage;
import com.github.xingjianluo.releaseorchestratorjava.model.ServiceState;
import com.github.xingjianluo.releaseorchestratorjava.repo.StateRepository;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程中断恢复：在第一次上下文里制造一个“停在 IN_PROGRESS 且服务仍被占用”的现场，
 * 随后新建上下文（重新执行启动恢复），断言发布被解释为 FAILED、占用被清理、可回滚，
 * 已发布的稳定版本与历史不丢失。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecoveryIntegrationTest extends AbstractIntegrationTest {

    @BeforeAll
    static void initDir() throws Exception {
        initWorkDir(RecoveryIntegrationTest.class);
    }

    private static final Logger log = LoggerFactory.getLogger(RecoveryIntegrationTest.class);

    @Autowired
    private ReleaseOrchestrator orchestrator;
    @Autowired
    private StateRepository repository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void interruptedInProgressReleaseIsRecoveredToFailedAndCanRollBack() throws Exception {
        // 1) v1.0.0 完整上线
        ReleaseRecord v1 = orchestrator.submit(
                request("delta", "1.0.0", "r1", List.of(), null));
        orchestrator.advance(v1.getReleaseId());
        orchestrator.advance(v1.getReleaseId());
        orchestrator.advance(v1.getReleaseId());

        // 2) v1.1.0 推进到 CANARY 后模拟进程中断：直接把发布记录改成 IN_PROGRESS，
        //    并把服务 active 标记恢复成占用中（崩溃现场）。
        ReleaseRecord v2 = orchestrator.submit(
                request("delta", "1.1.0", "r2", List.of(), null));
        orchestrator.advance(v2.getReleaseId()); // STAGING
        // 手动构造崩溃现场
        ReleaseRecord interrupted = repository.findRelease(v2.getReleaseId());
        assertThat(interrupted.getCompletedStages())
                .containsExactly(ReleaseStage.BUILD, ReleaseStage.STAGING);
        interrupted.setState(ReleaseState.IN_PROGRESS);
        interrupted.setFailureStage(null);
        interrupted.setFailureReasonCode(null);
        interrupted.setFailureReason(null);
        interrupted.setUpdatedAt(Instant.now());
        repository.saveRelease(interrupted);
        repository.updateServiceState("delta", s -> s.setActiveReleaseId(v2.getReleaseId()));

        log.info("已构造崩溃现场：发布 {} 停留在 IN_PROGRESS，服务被占用", v2.getReleaseId());

        // 3) 用相同状态目录重建 Spring 上下文：构造全新组件并显式执行恢复
        ObjectMapper mapper = objectMapper;
        StateRepository freshRepo = new StateRepository(
                workDir.resolve("state").toString(), mapper);
        freshRepo.init();
        freshRepo.recoverInterruptedReleases();
        ReleaseOrchestrator freshOrchestrator = new ReleaseOrchestrator(
                new com.github.xingjianluo.releaseorchestratorjava.version.VersionGenerator(),
                new com.github.xingjianluo.releaseorchestratorjava.artifact.ArtifactStore(
                        workDir.resolve("artifacts").toString(), mapper),
                new com.github.xingjianluo.releaseorchestratorjava.health.HealthGate(),
                freshRepo);

        ReleaseRecord recovered = freshOrchestrator.get(v2.getReleaseId());
        log.info("恢复结论: 状态={} 失败阶段={} 原因={}",
                recovered.getState(), recovered.getFailureStage(), recovered.getFailureReason());
        assertThat(recovered.getState()).isEqualTo(ReleaseState.FAILED);
        assertThat(recovered.getFailureStage()).isEqualTo(ReleaseStage.CANARY);
        assertThat(recovered.getFailureReasonCode()).isEqualTo("INVALID_STAGE_TRANSITION");
        // 已完成的阶段没有丢失
        assertThat(recovered.getCompletedStages())
                .containsExactly(ReleaseStage.BUILD, ReleaseStage.STAGING);

        ServiceState state = freshRepo.getServiceState("delta");
        assertThat(state.getActiveReleaseId()).isNull();
        // 线上稳定版本与历史未丢失
        assertThat(state.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(state.getCurrentReleaseId()).isEqualTo(v1.getReleaseId());
        assertThat(state.getPendingRollbackReleaseId()).isEqualTo(v2.getReleaseId());
        assertThat(state.getHistory()).isNotEmpty();

        // 4) 恢复后可回滚，结论一致
        ReleaseRecord rolledBack = freshOrchestrator.rollback("delta");
        assertThat(rolledBack.getState()).isEqualTo(ReleaseState.ROLLED_BACK);
        assertThat(freshRepo.getServiceState("delta").getCurrentVersion()).isEqualTo("1.0.0");
        log.info("恢复后回滚完成，线上版本仍为 1.0.0，历史条目数={}",
                freshRepo.getServiceState("delta").getHistory().size());
    }
}
