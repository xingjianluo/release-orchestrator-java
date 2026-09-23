package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 失败终态、回滚到上一稳定版本、回滚语义与幂等。 */
class RollbackFailureTest extends AbstractOrchestratorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Release complete(String service, String commit, String content) {
        Release r = orchestrator.submit(new ReleaseRequest(service, commit, content, null, null));
        for (int i = 0; i < StageName.ordered().size(); i++) {
            r = orchestrator.advance(r.getReleaseId());
        }
        return r;
    }

    private Release failAt(String service, String commit, String content, String fault) {
        Release r = orchestrator.submit(new ReleaseRequest(service, commit, content, null, fault));
        Release last = r;
        for (int i = 0; i < StageName.ordered().size(); i++) {
            last = orchestrator.advance(last.getReleaseId());
            if (last.getStatus() == ReleaseStatus.FAILED) {
                break;
            }
        }
        return last;
    }

    @Test
    void rollbackRestoresPreviousStableVersionPointer() throws Exception {
        Release v1 = complete("wallet-api", "1111111111111111111111111111111111111111", "release-1");
        Release v2 = failAt("wallet-api", "2222222222222222222222222222222222222222", "release-2", "fail:PRODUCTION");
        assertEquals(ReleaseStatus.FAILED, v2.getStatus());

        Release rolledBack = orchestrator.rollback(v2.getReleaseId());
        assertEquals(ReleaseStatus.ROLLED_BACK, rolledBack.getStatus());
        assertEquals(v2.getVersion(), rolledBack.getRolledBackFromVersion());
        assertEquals(v1.getVersion(), rolledBack.getRolledBackToVersion());

        Path pointer = dataDir().resolve("deployments/wallet-api/current.json");
        JsonNode node = mapper.readTree(Files.readAllBytes(pointer));
        assertEquals(v1.getVersion(), node.get("version").asText());
        assertEquals(v1.getArtifactSha256(), node.get("artifactSha256").asText());
        testLog.info("final state: rolled back from={} to={}; pointer={}",
                rolledBack.getRolledBackFromVersion(), rolledBack.getRolledBackToVersion(), pointer);
    }

    @Test
    void rollbackWithoutAnyStableVersionIsRejectedWithDistinctCode() {
        Release failed = failAt("wallet-api", "3333333333333333333333333333333333333333", "first-ever", "fail:STAGING");
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> orchestrator.rollback(failed.getReleaseId()));
        assertEquals("ROLLBACK_NO_STABLE_VERSION", ex.getCode());
        assertEquals(ReleaseStatus.FAILED, orchestrator.get(failed.getReleaseId()).getStatus());
        testLog.info("final state: rollback rejected code={} message={}", ex.getCode(), ex.getMessage());
    }

    @Test
    void rollbackOfCompletedReleaseIsRejected() {
        Release ok = complete("wallet-api", "4444444444444444444444444444444444444444", "fine");
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> orchestrator.rollback(ok.getReleaseId()));
        assertEquals("ROLLBACK_NOT_ALLOWED", ex.getCode());
    }

    @Test
    void repeatedRollbackIsIdempotentAndKeepsHistory() {
        Release v1 = complete("loyalty-api", "5555555555555555555555555555555555555555", "stable");
        Release v2 = failAt("loyalty-api", "6666666666666666666666666666666666666666", "broken", "fail:CANARY");
        Release first = orchestrator.rollback(v2.getReleaseId());
        Release second = orchestrator.rollback(v2.getReleaseId());
        assertEquals(first.getStatus(), second.getStatus());
        assertEquals(v1.getVersion(), second.getRolledBackToVersion());

        long rollbackCompletedEvents = journal.readAll().stream()
                .filter(e -> "ROLLBACK_COMPLETED".equals(e.type())).count();
        assertEquals(1, rollbackCompletedEvents, "second rollback must not mutate history again");
        testLog.info("final state: idempotent rollback events={} target={}",
                rollbackCompletedEvents, second.getRolledBackToVersion());
    }

    @Test
    void rolledBackReleaseCannotBeAdvanced() {
        Release v1 = complete("loyalty-api", "7777777777777777777777777777777777777777", "stable2");
        Release v2 = failAt("loyalty-api", "8888888888888888888888888888888888888888", "broken2", "fail:STAGING");
        orchestrator.rollback(v2.getReleaseId());
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> orchestrator.advance(v2.getReleaseId()));
        assertEquals("RELEASE_ROLLED_BACK", ex.getCode());
        assertTrue(ex.getMessage().contains(v1.getVersion()));
    }
}
