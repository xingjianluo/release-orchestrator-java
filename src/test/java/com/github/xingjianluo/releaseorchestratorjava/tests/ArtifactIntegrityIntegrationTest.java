package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.artifact.ArtifactStore;
import com.github.xingjianluo.releaseorchestratorjava.error.ArtifactIntegrityException;
import com.github.xingjianluo.releaseorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseState;
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
class ArtifactIntegrityIntegrationTest extends AbstractIntegrationTest {

    @BeforeAll
    static void initDir() throws Exception {
        initWorkDir(ArtifactIntegrityIntegrationTest.class);
    }

    private static final Logger log = LoggerFactory.getLogger(ArtifactIntegrityIntegrationTest.class);

    @Autowired
    private ReleaseOrchestrator orchestrator;
    @Autowired
    private StateRepository repository;
    @Autowired
    private ArtifactStore artifactStore;

    @Test
    void tamperedArtifactDuringRolloutFailsStageWithIntegrityCode() {
        ReleaseRecord record = orchestrator.submit(
                request("gateway", "2.3.4", "ref-234", List.of("x"), null));
        orchestrator.advance(record.getReleaseId()); // STAGING 成功

        // 模拟制品在磁盘上被外部篡改：下一个阶段部署准入必须失败
        artifactStore.tamperForTest(record.getArtifactId());
        log.info("已篡改制品 {}，期望 CANARY 推进以 ARTIFACT_INTEGRITY_FAILED 失败",
                record.getArtifactId());

        assertThatThrownBy(() -> orchestrator.advance(record.getReleaseId()))
                .isInstanceOf(ArtifactIntegrityException.class)
                .satisfies(ex -> assertThat(((ArtifactIntegrityException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ARTIFACT_INTEGRITY_FAILED));

        ReleaseRecord reloaded = repository.findRelease(record.getReleaseId());
        log.info("完整性失败终态: 状态={} 原因码={}", reloaded.getState(), reloaded.getFailureReasonCode());
        assertThat(reloaded.getState()).isEqualTo(ReleaseState.FAILED);
        assertThat(reloaded.getFailureReasonCode()).isEqualTo("ARTIFACT_INTEGRITY_FAILED");
        // 阶段列表不包含 CANARY：失败阶段没有被记为完成
        assertThat(reloaded.getCompletedStages()).hasSize(2);

        // 发布失败后仍可回滚到稳定版本（本例尚无历史版本，属无版本可回退的确认恢复）
        ReleaseRecord rolledBack = orchestrator.rollback("gateway");
        assertThat(rolledBack.getState()).isEqualTo(ReleaseState.ROLLED_BACK);
    }
}
