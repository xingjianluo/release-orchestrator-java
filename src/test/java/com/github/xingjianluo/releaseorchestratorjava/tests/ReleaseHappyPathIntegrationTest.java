package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.error.DuplicateReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.releaseorchestratorjava.error.InvalidVersionException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStage;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseState;
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
class ReleaseHappyPathIntegrationTest extends AbstractIntegrationTest {

    @BeforeAll
    static void initDir() throws Exception {
        initWorkDir(ReleaseHappyPathIntegrationTest.class);
    }

    private static final Logger log = LoggerFactory.getLogger(ReleaseHappyPathIntegrationTest.class);

    @Autowired
    private ReleaseOrchestrator orchestrator;
    @Autowired
    private StateRepository repository;

    @Test
    void fullStagedReleaseIsDeterministicAndReusable() {
        ReleaseRecord build = orchestrator.submit(
                request("orders", "1.0.0", "ref-100", List.of("init"), null));
        log.info("提交后: id={} 状态={} 已完成阶段={} 制品={}",
                build.getReleaseId(), build.getState(), build.getCompletedStages(), build.getArtifactId());
        assertThat(build.getState()).isEqualTo(ReleaseState.IN_PROGRESS);
        assertThat(build.getCompletedStages()).containsExactly(ReleaseStage.BUILD);

        ReleaseRecord staging = orchestrator.advance(build.getReleaseId());
        assertThat(staging.getCompletedStages()).containsExactly(ReleaseStage.BUILD, ReleaseStage.STAGING);
        ReleaseRecord canary = orchestrator.advance(build.getReleaseId());
        assertThat(canary.getCompletedStages())
                .containsExactly(ReleaseStage.BUILD, ReleaseStage.STAGING, ReleaseStage.CANARY);

        ReleaseRecord stable = orchestrator.advance(build.getReleaseId());
        log.info("最终发布状态: id={} 状态={} 已完成阶段={}",
                stable.getReleaseId(), stable.getState(), stable.getCompletedStages());
        assertThat(stable.getState()).isEqualTo(ReleaseState.STABLE);
        assertThat(stable.getCompletedStages()).containsExactly(
                ReleaseStage.BUILD, ReleaseStage.STAGING, ReleaseStage.CANARY, ReleaseStage.FULL);

        ServiceState state = repository.getServiceState("orders");
        assertThat(state.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(state.getCurrentReleaseId()).isEqualTo(build.getReleaseId());
        assertThat(state.getActiveReleaseId()).isNull();
        assertThat(state.getHistory()).extracting(h -> h.getEvent())
                .contains("SUBMITTED", "STAGE_SUCCEEDED", "STABLE");
    }

    @Test
    void duplicateSubmissionOfSameInputIsRejectedWithDistinctCode() {
        var req = request("payments", "1.0.0", "ref-a", List.of("n"), null);
        ReleaseRecord first = orchestrator.submit(req);
        log.info("首次提交成功 id={}", first.getReleaseId());

        assertThatThrownBy(() -> orchestrator.submit(request(
                "payments", "1.0.0", "ref-a", List.of("n"), null)))
                .isInstanceOf(DuplicateReleaseException.class)
                .satisfies(ex -> {
                    DuplicateReleaseException d = (DuplicateReleaseException) ex;
                    assertThat(d.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RELEASE);
                    log.info("重复提交被拒绝，原因码={} 原因={}", d.getErrorCode(), d.getMessage());
                });

        // 即使带故障注入钩子，相同输入仍是同一发布标识，重复提交依旧被拒绝。
        assertThatThrownBy(() -> orchestrator.submit(request(
                "payments", "1.0.0", "ref-a", List.of("n"), "CANARY")))
                .isInstanceOf(DuplicateReleaseException.class);

        // 只有一份发布记录，没有产生重复部署。
        assertThat(repository.findByService("payments")).hasSize(1);
    }

    @Test
    void illegalVersionFlowIsRejected() {
        orchestrator.submit(request("users", "1.2.0", "r", List.of(), null));
        // 全量上线 1.2.0
        var id = repository.findByService("users").get(0).getReleaseId();
        orchestrator.advance(id);
        orchestrator.advance(id);
        orchestrator.advance(id);

        // 版本降级
        assertThatThrownBy(() -> orchestrator.submit(request("users", "1.1.0", "r2", List.of(), null)))
                .isInstanceOf(InvalidVersionException.class)
                .satisfies(ex -> log.info("版本降级被拒绝 原因码={} 原因={}",
                        ((InvalidVersionException) ex).getErrorCode(), ex.getMessage()));

        // 重复版本（即使 sourceRef 不同）
        assertThatThrownBy(() -> orchestrator.submit(request("users", "1.2.0", "r-other", List.of(), null)))
                .isInstanceOf(InvalidVersionException.class)
                .satisfies(ex -> assertThat(((InvalidVersionException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_VERSION));
    }
}
