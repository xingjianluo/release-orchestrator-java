package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.releaseorchestratorjava.error.InvalidVersionException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.version.VersionGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 确定性版本/制品标识派生与非法版本拒绝（纯单元，无 IO）。 */
class VersionGeneratorTest {

    private static final Logger log = LoggerFactory.getLogger(VersionGeneratorTest.class);

    private final VersionGenerator generator = new VersionGenerator();

    private ReleaseRequest request(String service, String version, String ref, List<String> notes,
                                   String failAt) {
        ReleaseRequest r = new ReleaseRequest();
        r.setService(service);
        r.setVersion(version);
        r.setSourceRef(ref);
        r.setChangeNotes(notes);
        r.setFailAtStage(failAt);
        return r;
    }

    @Test
    void sameInputProducesSameIdentifiers() {
        ReleaseRequest a = request("svc", "1.2.0", "ref-abc", List.of("fix", "feat"), null);
        ReleaseRequest b = request("svc", "1.2.0", "ref-abc", List.of("feat", "fix"), null);

        String idA = generator.releaseId(a);
        String idB = generator.releaseId(b);
        log.info("输入相同（变更说明顺序不同）=> releaseIdA={} releaseIdB={}", idA, idB);

        assertThat(generator.resolveVersion(a)).isEqualTo("1.2.0");
        assertThat(idA).isEqualTo(idB);
        assertThat(generator.contentKey(a)).isEqualTo(generator.contentKey(b));
        assertThat(idA).startsWith("rel-");
    }

    @Test
    void failAtStageDoesNotAffectIdentity() {
        ReleaseRequest healthy = request("svc", "1.2.0", "ref", List.of(), null);
        ReleaseRequest failing = request("svc", "1.2.0", "ref", List.of(), "CANARY");
        log.info("故障注入钩子不参与标识: id健康={} id失败={}",
                generator.releaseId(healthy), generator.releaseId(failing));
        assertThat(generator.releaseId(healthy)).isEqualTo(generator.releaseId(failing));
    }

    @Test
    void differentInputProducesDifferentIdentifiers() {
        ReleaseRequest a = request("svc", "1.2.0", "ref-1", List.of(), null);
        ReleaseRequest b = request("svc", "1.2.1", "ref-2", List.of(), null);
        assertThat(generator.releaseId(a)).isNotEqualTo(generator.releaseId(b));
    }

    @Test
    void invalidVersionsAreRejectedWithDistinctCode() {
        for (String bad : List.of("", "   ", "1.2", "v1.2.0", "1.2.x", "1", "1.2.3.4")) {
            log.info("校验非法版本输入='{}' 期望 INVALID_VERSION", bad);
            assertThatThrownBy(() -> generator.resolveVersion(request("svc", bad, "r", List.of(), null)))
                    .isInstanceOf(InvalidVersionException.class)
                    .satisfies(ex -> assertThat(((InvalidVersionException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_VERSION));
        }
        assertThatThrownBy(() -> generator.resolveVersion(request("svc", null, "r", List.of(), null)))
                .isInstanceOf(InvalidVersionException.class);
        assertThatThrownBy(() -> generator.resolveVersion(request(" ", "1.0.0", "r", List.of(), null)))
                .isInstanceOf(InvalidVersionException.class);
    }

    @Test
    void prereleaseVersionAccepted() {
        assertThat(generator.resolveVersion(request("svc", "2.0.0-rc.1", "r", List.of(), null)))
                .isEqualTo("2.0.0-rc.1");
    }
}
