package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 每个阶段准入前的制品完整性校验：损坏制品必须拒绝进入该阶段。 */
class ArtifactGateAtStageTest extends AbstractOrchestratorTest {

    @Test
    void tamperedArtifactAfterBuildBlocksStagingWithChecksumCode() throws Exception {
        Release r = orchestrator.submit(new ReleaseRequest("edge-api",
                "9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a9a", "tamper-me", null, null));
        r = orchestrator.advance(r.getReleaseId()); // BUILD -> PASSED
        assertEquals(StageStatus.PASSED, r.getStages().get(StageName.BUILD).getStatus());
        final Release built = r;

        // 模拟磁盘上的制品在阶段之间被损坏。
        Path artifact = Paths.get(properties.getDataDir()).resolve(built.getArtifactUri());
        Files.write(artifact, "corrupted-bytes-after-build".getBytes(StandardCharsets.UTF_8));
        testLog.info("input: tampered artifact on disk uri={}", r.getArtifactUri());

        // 阶段失败被收敛为可解释终态（而非向上抛出），失败码可区分。
        Release failed = orchestrator.advance(built.getReleaseId());
        assertEquals(ReleaseStatus.FAILED, failed.getStatus());
        assertEquals(StageName.STAGING, failed.getFailureStage());
        assertEquals("ARTIFACT_CHECKSUM_MISMATCH", failed.getFailureCode());
        assertEquals(StageStatus.FAILED, failed.getStages().get(StageName.STAGING).getStatus());
        assertEquals(StageStatus.PENDING, failed.getStages().get(StageName.CANARY).getStatus());
        testLog.info("final state: stage entry refused, release FAILED code={} reason={}",
                failed.getFailureCode(), failed.getFailureReason());
    }
}
