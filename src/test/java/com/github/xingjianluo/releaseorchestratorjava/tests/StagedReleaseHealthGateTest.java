package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 严格按阶段推进、健康准入失败、终态不可再推进。 */
class StagedReleaseHealthGateTest extends AbstractOrchestratorTest {

    private Release submit(String content, String fault) {
        return orchestrator.submit(new ReleaseRequest("checkout-api",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", content, null, fault));
    }

    @Test
    void stagesAdvanceOneAtATimeInOrder() {
        Release r = submit("ordered", null);
        assertEquals(ReleaseStatus.PENDING, r.getStatus());

        r = orchestrator.advance(r.getReleaseId());
        assertEquals(StageStatus.PASSED, r.getStages().get(StageName.BUILD).getStatus());
        assertEquals(StageStatus.PENDING, r.getStages().get(StageName.STAGING).getStatus());
        assertEquals(StageStatus.PENDING, r.getStages().get(StageName.CANARY).getStatus());
        assertEquals(StageStatus.PENDING, r.getStages().get(StageName.PRODUCTION).getStatus());
        assertEquals(ReleaseStatus.IN_PROGRESS, r.getStatus());

        r = orchestrator.advance(r.getReleaseId());
        assertEquals(StageStatus.PASSED, r.getStages().get(StageName.STAGING).getStatus());
        assertEquals(StageStatus.PENDING, r.getStages().get(StageName.CANARY).getStatus());

        r = orchestrator.advance(r.getReleaseId());
        assertEquals(StageStatus.PASSED, r.getStages().get(StageName.CANARY).getStatus());
        assertEquals(StageStatus.PENDING, r.getStages().get(StageName.PRODUCTION).getStatus());

        r = orchestrator.advance(r.getReleaseId());
        assertEquals(ReleaseStatus.COMPLETED, r.getStatus());
        assertEquals(StageStatus.PASSED, r.getStages().get(StageName.PRODUCTION).getStatus());
        testLog.info("final state: completed in strict order version={}", r.getVersion());
    }

    @Test
    void failedHealthGateStopsAllLaterStages() {
        Release r = submit("gated", "fail:CANARY");
        r = orchestrator.advance(r.getReleaseId()); // BUILD
        r = orchestrator.advance(r.getReleaseId()); // STAGING
        r = orchestrator.advance(r.getReleaseId()); // CANARY -> fail
        final Release failed = r;
        testLog.info("decision: canary failed status={} code={} stage={}",
                r.getStatus(), r.getFailureCode(), r.getFailureStage());

        assertEquals(ReleaseStatus.FAILED, r.getStatus());
        assertEquals(StageName.CANARY, r.getFailureStage());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", r.getFailureCode());
        assertEquals(StageStatus.FAILED, r.getStages().get(StageName.CANARY).getStatus());
        assertEquals(StageStatus.PENDING, r.getStages().get(StageName.PRODUCTION).getStatus());

        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> orchestrator.advance(failed.getReleaseId()));
        assertEquals("RELEASE_FAILED", ex.getCode());
        assertTrue(ex.getMessage().contains("CANARY_HEALTH_CHECK_FAILED"));
        testLog.info("final state: failed release cannot be advanced code={}", ex.getCode());
    }

    @Test
    void buildFailureIsReportedWithItsOwnCodeAndLeavesNoStableVersion() {
        Release r = submit("broken-build", "fail:BUILD");
        r = orchestrator.advance(r.getReleaseId());
        assertEquals(ReleaseStatus.FAILED, r.getStatus());
        assertEquals(StageName.BUILD, r.getFailureStage());
        assertEquals("BUILD_HEALTH_CHECK_FAILED", r.getFailureCode());
        for (StageName later : new StageName[]{StageName.STAGING, StageName.CANARY, StageName.PRODUCTION}) {
            assertEquals(StageStatus.PENDING, r.getStages().get(later).getStatus());
        }
        testLog.info("final state: build failure code={} reason={}",
                r.getFailureCode(), r.getFailureReason());
    }

    @Test
    void releaseNotFoundHasDistinctCode() {
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> orchestrator.advance("rel-doesnotexist"));
        assertEquals("RELEASE_NOT_FOUND", ex.getCode());
    }
}
