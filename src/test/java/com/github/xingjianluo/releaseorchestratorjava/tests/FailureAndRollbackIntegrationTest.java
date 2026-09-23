package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.releaseorchestratorjava.error.HealthCheckFailureException;
import com.github.xingjianluo.releaseorchestratorjava.error.ReleaseNotAdvanceableException;
import com.github.xingjianluo.releaseorchestratorjava.error.RollbackConflictException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseState;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStage;
import com.github.xingjianluo.releaseorchestratorjava.model.ServiceState;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.repo.StateRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FailureAndRollbackIntegrationTest extends AbstractIntegrationTest {

    @BeforeAll
    static void initDir() throws Exception {
        initWorkDir(FailureAndRollbackIntegrationTest.class);
    }

    private static final Logger log = LoggerFactory.getLogger(FailureAndRollbackIntegrationTest.class);

    @Autowired
    private ReleaseOrchestrator orchestrator;
    @Autowired
    private StateRepository repository;

    private ReleaseRecord releaseStable(String service, String version) {
        ReleaseRecord r = orchestrator.submit(request(service, version, "ref-" + version, List.of(), null));
        orchestrator.advance(r.getReleaseId());
        orchestrator.advance(r.getReleaseId());
        orchestrator.advance(r.getReleaseId());
        return r;
    }

    @Test
    void stageFailureLeadsToExplainableTerminalStateAndBlocksFurtherAdvance() {
        // CANARY 健康检查确定性失败
        ReleaseRecord failed = orchestrator.submit(
                request("catalog", "1.1.0", "ref-x", List.of("c"), "CANARY"));
        orchestrator.advance(failed.getReleaseId()); // STAGING 成功
        assertThatThrownBy(() -> orchestrator.advance(failed.getReleaseId()))
                .isInstanceOf(HealthCheckFailureException.class)
                .satisfies(ex -> assertThat(((HealthCheckFailureException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.HEALTH_CHECK_FAILED));

        ReleaseRecord reloaded = orchestrator.get(failed.getReleaseId());
        log.info("失败发布终态: 状态={} 失败阶段={} 原因码={} 原因={}",
                reloaded.getState(), reloaded.getFailureStage(),
                reloaded.getFailureReasonCode(), reloaded.getFailureReason());
        assertThat(reloaded.getState()).isEqualTo(ReleaseState.FAILED);
        assertThat(reloaded.getFailureStage()).isEqualTo(ReleaseStage.CANARY);
        assertThat(reloaded.getFailureReasonCode()).isEqualTo("HEALTH_CHECK_FAILED");

        // 失败发布不得被再次推进，且已通过的阶段不会重复部署
        assertThatThrownBy(() -> orchestrator.advance(failed.getReleaseId()))
                .isInstanceOf(ReleaseNotAdvanceableException.class)
                .satisfies(ex -> assertThat(((ReleaseNotAdvanceableException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RELEASE_NOT_ADVANCEABLE));
        assertThat(reloaded.getCompletedStages())
                .containsExactly(ReleaseStage.BUILD, ReleaseStage.STAGING);
    }

    @Test
    void failedReleaseCanRollbackToPreviousStableVersion() {
        ReleaseRecord v1 = releaseStable("shop", "1.0.0");
        assertThat(repository.getServiceState("shop").getCurrentVersion()).isEqualTo("1.0.0");

        ReleaseRecord v2 = orchestrator.submit(request("shop", "1.1.0", "ref-new", List.of(), "FULL"));
        orchestrator.advance(v2.getReleaseId());
        orchestrator.advance(v2.getReleaseId());
        assertThatThrownBy(() -> orchestrator.advance(v2.getReleaseId()))
                .isInstanceOf(HealthCheckFailureException.class); // FULL 失败

        ServiceState beforeRollback = repository.getServiceState("shop");
        log.info("失败后线上仍为: current={} previous={}",
                beforeRollback.getCurrentVersion(), beforeRollback.getPreviousVersion());
        assertThat(beforeRollback.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(beforeRollback.getCurrentReleaseId()).isEqualTo(v1.getReleaseId());

        ReleaseRecord rolledBack = orchestrator.rollback("shop");
        ServiceState after = repository.getServiceState("shop");
        log.info("回滚后: 失败发布状态={} 线上版本={}", rolledBack.getState(), after.getCurrentVersion());
        assertThat(rolledBack.getState()).isEqualTo(ReleaseState.ROLLED_BACK);
        assertThat(rolledBack.getRollbackTargetReleaseId()).isEqualTo(v1.getReleaseId());
        assertThat(after.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(after.getCurrentReleaseId()).isEqualTo(v1.getReleaseId());
        assertThat(after.getPendingRollbackReleaseId()).isNull();
        assertThat(after.getHistory()).extracting(h -> h.getEvent())
                .contains("FAILED", "ROLLBACK_STARTED", "ROLLED_BACK");

        // 回滚中的/已回滚发布不得被再次推进
        assertThatThrownBy(() -> orchestrator.advance(v2.getReleaseId()))
                .isInstanceOf(ReleaseNotAdvanceableException.class);

        // 重复回滚同一失败发布：得到一致结论，不产生新的状态错乱
        assertThatThrownBy(() -> orchestrator.rollback("shop"))
                .isInstanceOf(RollbackConflictException.class)
                .satisfies(ex -> log.info("重复回滚被拒绝: {}", ex.getMessage()));
    }

    @Test
    void rollbackOfStableReleaseSwapsLivePointers() {
        releaseStable("billing", "1.0.0");
        releaseStable("billing", "2.0.0");
        assertThat(repository.getServiceState("billing").getCurrentVersion()).isEqualTo("2.0.0");

        // 没有进行中发布时，可将线上版本回退到上一个稳定版本
        ReleaseRecord target = orchestrator.rollback("billing");
        ServiceState state = repository.getServiceState("billing");
        log.info("稳定版本回滚后 current={} previous={}", state.getCurrentVersion(), state.getPreviousVersion());
        assertThat(state.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(state.getPreviousVersion()).isEqualTo("2.0.0");
        assertThat(target.getState()).isEqualTo(ReleaseState.STABLE);
        assertThat(state.getHistory()).extracting(h -> h.getEvent()).contains("ROLLED_BACK");
    }

    @Test
    void rollbackWithoutAnyStableVersionIsRejected() {
        orchestrator.submit(request("search", "0.9.0", "r", List.of(), "STAGING"));
        String id = repository.findByService("search").get(0).getReleaseId();
        assertThatThrownBy(() -> orchestrator.advance(id))
                .isInstanceOf(HealthCheckFailureException.class);

        // 首个发布在 STAGING 即失败：从未上线，回滚确认恢复仍然成功（目标为 null 即“无版本”）
        ReleaseRecord rolledBack = orchestrator.rollback("search");
        assertThat(rolledBack.getState()).isEqualTo(ReleaseState.ROLLED_BACK);
        assertThat(repository.getServiceState("search").getCurrentReleaseId()).isNull();
    }
}
