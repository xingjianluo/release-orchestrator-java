package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.xingjianluo.releaseorchestratorjava.artifact.Artifact;
import com.github.xingjianluo.releaseorchestratorjava.artifact.ArtifactStore;
import com.github.xingjianluo.releaseorchestratorjava.error.ArtifactIntegrityException;
import com.github.xingjianluo.releaseorchestratorjava.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 制品确定性构建/复用与完整性规则（本地临时目录，完全离线）。 */
class ArtifactStoreTest {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStoreTest.class);

    @TempDir
    Path tempDir;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        store = new ArtifactStore(tempDir.resolve("artifacts").toString(), mapper);
    }

    @Test
    void deterministicBuildAndReuse() {
        Artifact a1 = store.build("svc", "1.0.0", "key-1");
        Artifact a2 = store.build("svc", "1.0.0", "key-1");
        log.info("第一次构建 id={} reused={}；第二次构建 id={} reused={}",
                a1.getArtifactId(), a1.isReused(), a2.getArtifactId(), a2.isReused());

        assertThat(a1.getArtifactId()).isEqualTo(a2.getArtifactId());
        assertThat(a1.getChecksum()).isEqualTo(a2.getChecksum());
        assertThat(a1.isReused()).isFalse();
        assertThat(a2.isReused()).isTrue();
        assertThat(a2.getChecksum()).startsWith(a2.getArtifactId().substring(4));
    }

    @Test
    void differentContentProducesDifferentArtifact() {
        Artifact a = store.build("svc", "1.0.0", "key-a");
        Artifact b = store.build("svc", "1.0.1", "key-b");
        assertThat(a.getArtifactId()).isNotEqualTo(b.getArtifactId());
    }

    @Test
    void tamperedArtifactFailsIntegrityVerification() {
        Artifact a = store.build("svc", "1.0.0", "key-tamper");
        log.info("篡改制品前 verify 通过，artifactId={}", a.getArtifactId());
        store.verify(a.getArtifactId(), a.getChecksum());

        store.tamperForTest(a.getArtifactId());
        log.info("已模拟磁盘篡改，期望读取与 verify 均以 ARTIFACT_INTEGRITY_FAILED 拒绝");
        assertThatThrownBy(() -> store.get(a.getArtifactId()))
                .isInstanceOf(ArtifactIntegrityException.class)
                .satisfies(ex -> assertThat(((ArtifactIntegrityException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ARTIFACT_INTEGRITY_FAILED));
        assertThatThrownBy(() -> store.verify(a.getArtifactId(), a.getChecksum()))
                .isInstanceOf(ArtifactIntegrityException.class);
    }

    @Test
    void missingArtifactFailsWithIntegrityCode() {
        assertThatThrownBy(() -> store.get("art-doesnotexist"))
                .isInstanceOf(ArtifactIntegrityException.class)
                .satisfies(ex -> assertThat(((ArtifactIntegrityException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ARTIFACT_INTEGRITY_FAILED));
    }
}
