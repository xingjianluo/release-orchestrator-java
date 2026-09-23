package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.artifact.ArtifactStore;
import com.github.xingjianluo.releaseorchestratorjava.model.ArtifactCoordinates;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import com.github.xingjianluo.releaseorchestratorjava.version.VersionCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确定性生成、复用、重复提交拒绝、制品完整性。 */
class DeterministicVersionArtifactTest extends AbstractOrchestratorTest {

    @Autowired
    private ArtifactStore artifactStore;

    @Autowired
    private VersionCalculator versionCalculator;

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private ReleaseRequest request(String content, String fault) {
        return new ReleaseRequest("billing-api", COMMIT, content,
                "key-" + content.hashCode(), fault);
    }

    private Release happyPath(String content) {
        Release r = orchestrator.submit(request(content, null));
        for (int i = 0; i < StageName.ordered().size(); i++) {
            r = orchestrator.advance(r.getReleaseId());
        }
        return r;
    }

    @Test
    void identicalInputsProduceIdenticalVersionAndArtifactAsCalculated() {
        String content = "payload-v1";
        Release r = happyPath(content);
        String expectedVersion = versionCalculator.calculateVersion("billing-api", COMMIT, content);
        byte[] expectedBytes = versionCalculator.buildArtifactBytes("billing-api", COMMIT, content, expectedVersion);
        String expectedSha = com.github.xingjianluo.releaseorchestratorjava.version
                .DefaultVersionCalculator.sha256Hex(expectedBytes);
        assertEquals(expectedVersion, r.getVersion());
        assertEquals(expectedSha, r.getArtifactSha256());
        assertEquals(expectedBytes.length, r.getArtifactSize());
        assertEquals(ReleaseStatus.COMPLETED, r.getStatus());
        testLog.info("final state: deterministic version={} sha256={} bytes={}",
                r.getVersion(), r.getArtifactSha256(), expectedBytes.length);
    }

    @Test
    void differentContentProducesDifferentVersionAndArtifact() {
        Release a = happyPath("payload-v1");
        Release b = happyPath("payload-v2");
        testLog.info("decision: versionA={} versionB={}", a.getVersion(), b.getVersion());
        assertEquals(ReleaseStatus.COMPLETED, a.getStatus());
        assertEquals(ReleaseStatus.COMPLETED, b.getStatus());
        assertTrue(!a.getVersion().equals(b.getVersion()));
        assertTrue(!a.getArtifactSha256().equals(b.getArtifactSha256()));
        assertTrue(!a.getReleaseId().equals(b.getReleaseId()));
    }

    @Test
    void duplicateSubmissionIsRejectedWithDistinctCode() {
        Release r = orchestrator.submit(request("dup-content", null));
        testLog.info("decision: first submit accepted id={} version={}", r.getReleaseId(), r.getVersion());
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> orchestrator.submit(request("dup-content", "fail:CANARY")));
        assertEquals("DUPLICATE_SUBMISSION", ex.getCode());
        testLog.info("final state: duplicate rejected code={} message={}", ex.getCode(), ex.getMessage());
    }

    @Test
    void invalidServiceNameAndCommitAreRejectedWithDistinctCodes() {
        ReleaseException badService = assertThrows(ReleaseException.class,
                () -> orchestrator.submit(new ReleaseRequest("BAD NAME", COMMIT, "x", null, null)));
        assertEquals("INVALID_SERVICE_NAME", badService.getCode());

        ReleaseException badCommit = assertThrows(ReleaseException.class,
                () -> orchestrator.submit(new ReleaseRequest("billing-api", "XYZ", "x", null, null)));
        assertEquals("INVALID_COMMIT", badCommit.getCode());
        testLog.info("final state: validation codes {} / {}",
                badService.getCode(), badCommit.getCode());
    }

    @Test
    void advancingCompletedReleaseIsRejectedAndNoDuplicateDeployment() {
        Release r = happyPath("repeatable");
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> orchestrator.advance(r.getReleaseId()));
        assertEquals("RELEASE_ALREADY_COMPLETED", ex.getCode());
        long deployedEvents = journal.readAll().stream()
                .filter(e -> "STAGE_DEPLOYED".equals(e.type())).count();
        assertEquals(3, deployedEvents); // 三个部署阶段各仅一次
        for (StageName stage : StageName.ordered()) {
            assertEquals(StageStatus.PASSED, r.getStages().get(stage).getStatus());
        }
        testLog.info("final state: {} deployedEvents={} (no duplicate deployment)",
                ex.getCode(), deployedEvents);
    }

    @Test
    void repeatedMaterializationReusesArtifactFile() {
        byte[] bytes = "artifact-body".getBytes(StandardCharsets.UTF_8);
        ArtifactCoordinates first = artifactStore.materialize("billing-api", "1.0.0-a", bytes);
        ArtifactCoordinates again = artifactStore.materialize("billing-api", "1.0.0-a", bytes);
        assertEquals(first, again);
        assertTrue(Files.exists(Paths.get(properties.getDataDir()).resolve(first.uri())));
        testLog.info("decision: artifact reused uri={} sha256={}", first.uri(), first.sha256());
    }

    @Test
    void conflictingContentAtArtifactPathIsNeverSilentlyOverwritten() throws java.io.IOException {
        byte[] bytes = "artifact-body-x".getBytes(StandardCharsets.UTF_8);
        ArtifactCoordinates coords = artifactStore.materialize("billing-api", "1.0.0-x", bytes);
        Path path = Paths.get(properties.getDataDir()).resolve(coords.uri());
        Files.write(path, "tampered-different-bytes".getBytes(StandardCharsets.UTF_8));
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> artifactStore.materialize("billing-api", "1.0.0-x", bytes));
        assertEquals("ARTIFACT_CONTENT_CONFLICT", ex.getCode());
        testLog.info("final state: overwrite refused code={}", ex.getCode());
    }

    @Test
    void corruptedArtifactFailsIntegrityVerificationWithDistinctCode() throws java.io.IOException {
        byte[] bytes = "artifact-body-y".getBytes(StandardCharsets.UTF_8);
        ArtifactCoordinates coords = artifactStore.materialize("billing-api", "1.0.0-y", bytes);
        Path path = Paths.get(properties.getDataDir()).resolve(coords.uri());
        Files.write(path, "corrupted".getBytes(StandardCharsets.UTF_8));
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> artifactStore.verify(coords));
        assertEquals("ARTIFACT_CHECKSUM_MISMATCH", ex.getCode());
        testLog.info("final state: integrity check failed code={} message={}",
                ex.getCode(), ex.getMessage());
    }
}
