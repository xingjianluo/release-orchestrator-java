package com.github.xingjianluo.releaseorchestratorjava.artifact;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.ArtifactCoordinates;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.version.DefaultVersionCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件制品仓库。
 * <ul>
 *   <li>路径编码内容摘要：artifacts/{serviceName}/{sha256}.bin，相同内容必然命中同一路径；</li>
 *   <li>已存在且字节一致 => 复用；已存在但字节不一致 => 拒绝（绝不静默覆盖）；</li>
 *   <li>写入走临时文件 + 原子 move，进程中断只会留下 tmp，不会出现半成品制品；</li>
 *   <li>{@link #verify} 在每个阶段准入前重算摘要，损坏即 ARTIFACT_CHECKSUM_MISMATCH。</li>
 * </ul>
 */
@Component
public class LocalArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(LocalArtifactStore.class);

    private final OrchestratorProperties properties;

    public LocalArtifactStore(OrchestratorProperties properties) {
        this.properties = properties;
    }

    private Path root() {
        return properties.dataPath();
    }

    @Override
    public synchronized ArtifactCoordinates materialize(String serviceName, String version, byte[] content) {
        String sha256 = DefaultVersionCalculator.sha256Hex(content);
        Path target = artifactPath(serviceName, sha256);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                byte[] existing = Files.readAllBytes(target);
                String existingSha = DefaultVersionCalculator.sha256Hex(existing);
                if (existingSha.equals(sha256)) {
                    log.info("artifact reuse service={} version={} sha256={} bytes={} (already present)",
                            serviceName, version, sha256, content.length);
                    return new ArtifactCoordinates(relativeUri(target), sha256, content.length);
                }
                throw new ReleaseException("ARTIFACT_CONTENT_CONFLICT",
                        "artifact path already exists with different content: " + target
                                + " (expected sha256=" + sha256 + ", actual sha256=" + existingSha + ")");
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
            log.info("artifact materialized service={} version={} sha256={} bytes={} path={}",
                    serviceName, version, sha256, content.length, target);
            return new ArtifactCoordinates(relativeUri(target), sha256, content.length);
        } catch (IOException | UncheckedIOException e) {
            throw new ReleaseException("ARTIFACT_IO_ERROR",
                    "failed to materialize artifact for " + serviceName + "@" + version + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void verify(ArtifactCoordinates coordinates) {
        Path path = root().resolve(coordinates.uri());
        if (!Files.isRegularFile(path)) {
            throw new ReleaseException("ARTIFACT_MISSING",
                    "artifact not found on disk: " + coordinates.uri());
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            String actual = DefaultVersionCalculator.sha256Hex(bytes);
            if (!actual.equals(coordinates.sha256())) {
                throw new ReleaseException("ARTIFACT_CHECKSUM_MISMATCH",
                        "artifact integrity check failed for " + coordinates.uri()
                                + ": expected sha256=" + coordinates.sha256() + ", actual sha256=" + actual);
            }
            if (bytes.length != coordinates.size()) {
                throw new ReleaseException("ARTIFACT_SIZE_MISMATCH",
                        "artifact size mismatch for " + coordinates.uri()
                                + ": expected=" + coordinates.size() + ", actual=" + bytes.length);
            }
            log.debug("artifact verified ok uri={} sha256={}", coordinates.uri(), actual);
        } catch (IOException e) {
            throw new ReleaseException("ARTIFACT_IO_ERROR",
                    "failed to verify artifact " + coordinates.uri() + ": " + e.getMessage(), e);
        }
    }

    private Path artifactPath(String serviceName, String sha256) {
        return root().resolve("artifacts").resolve(serviceName).resolve(sha256 + ".bin");
    }

    private String relativeUri(Path target) {
        return root().toAbsolutePath().relativize(target.toAbsolutePath()).toString().replace('\\', '/');
    }
}
