package com.github.xingjianluo.releaseorchestratorjava.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.error.ArtifactIntegrityException;
import com.github.xingjianluo.releaseorchestratorjava.version.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地内容寻址制品库。
 *
 * <ul>
 *   <li>制品字节由内容键确定性构建，SHA-256 作为制品标识；相同输入复用既有制品，不重复写入；</li>
 *   <li>已存在的制品文件绝不静默覆盖：内容一致则复用，内容被篡改（校验不符）则以
 *       {@code ARTIFACT_INTEGRITY_FAILED} 拒绝；构建写入采取临时文件 + 原子移动；</li>
 *   <li>每次读取/部署都重新计算校验和，完整性失败给出可区分原因。</li>
 * </ul>
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final Path root;
    private final ObjectMapper objectMapper;

    public ArtifactStore(@Value("${release.artifact-dir:target/release-state/artifacts}") String artifactDir,
                         ObjectMapper objectMapper) {
        this.root = Path.of(artifactDir);
        this.objectMapper = objectMapper;
    }

    public Artifact build(String service, String version, String contentKey) {
        byte[] content = deterministicContent(service, version, contentKey);
        String checksum = HashUtil.sha256Hex(content);
        String artifactId = "art-" + checksum.substring(0, 24);

        Path dataFile = root.resolve(artifactId + ".bin");
        Path metaFile = root.resolve(artifactId + ".json");
        try {
            Files.createDirectories(root);
            if (Files.exists(dataFile)) {
                // 既有制品绝不静默覆盖：只允许“内容一致的复用”，否则判定完整性失败。
                byte[] existing = Files.readAllBytes(dataFile);
                String existingChecksum = HashUtil.sha256Hex(existing);
                if (!checksum.equals(existingChecksum)) {
                    throw new ArtifactIntegrityException(
                            "制品 " + artifactId + " 已存在但内容与预期不一致，拒绝覆盖（预期 "
                                    + checksum + "，实际 " + existingChecksum + "）");
                }
                log.info("复用既有制品 artifactId={} service={} version={} 复用=true", artifactId, service, version);
                return readArtifact(artifactId, service, version, contentKey, content, true);
            }

            Path tmp = root.resolve(artifactId + ".bin.tmp");
            Files.write(tmp, content);
            try {
                Files.move(tmp, dataFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Artifact artifact = new Artifact(artifactId, checksum, contentKey, service, version, content, false);
            Files.writeString(metaFile, objectMapper.writeValueAsString(
                    new ArtifactMeta(artifactId, checksum, service, version, contentKey, content.length)),
                    StandardCharsets.UTF_8);
            log.info("构建并持久化制品 artifactId={} service={} version={} 复用=false 字节数={}",
                    artifactId, service, version, content.length);
            return artifact;
        } catch (ArtifactIntegrityException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("写入制品失败: " + artifactId, e);
        }
    }

    public Artifact get(String artifactId) {
        Path dataFile = root.resolve(artifactId + ".bin");
        Path metaFile = root.resolve(artifactId + ".json");
        if (!Files.exists(dataFile) || !Files.exists(metaFile)) {
            throw new ArtifactIntegrityException("制品不存在或缺失文件: " + artifactId);
        }
        try {
            ArtifactMeta meta = objectMapper.readValue(Files.readString(metaFile, StandardCharsets.UTF_8),
                    ArtifactMeta.class);
            byte[] content = Files.readAllBytes(dataFile);
            String actual = HashUtil.sha256Hex(content);
            if (!meta.checksum().equals(actual)) {
                throw new ArtifactIntegrityException(
                        "制品完整性校验失败 " + artifactId + "：期望 " + meta.checksum() + "，实际 " + actual);
            }
            if (!actual.startsWith(meta.artifactId().substring(4))) {
                throw new ArtifactIntegrityException(
                        "制品标识与内容校验和不匹配: " + artifactId);
            }
            return new Artifact(meta.artifactId(), meta.checksum(), meta.contentKey(),
                    meta.service(), meta.version(), content, true);
        } catch (ArtifactIntegrityException e) {
            throw e;
        } catch (IOException e) {
            throw new ArtifactIntegrityException("读取制品失败: " + artifactId + "，原因: " + e.getMessage());
        }
    }

    /** 部署前准入：期望校验和与磁盘内容必须一致。 */
    public void verify(String artifactId, String expectedChecksum) {
        Artifact artifact = get(artifactId);
        if (!artifact.getChecksum().equals(expectedChecksum)) {
            throw new ArtifactIntegrityException(
                    "制品完整性校验失败 " + artifactId + "：期望 " + expectedChecksum
                            + "，实际 " + artifact.getChecksum());
        }
        log.info("制品完整性校验通过 artifactId={}", artifactId);
    }

    /** 测试钩子：模拟制品在磁盘上被外部篡改（仅用于测试，不修改元数据）。 */
    public void tamperForTest(String artifactId) {
        Path dataFile = root.resolve(artifactId + ".bin");
        try {
            if (!Files.exists(dataFile)) {
                throw new ArtifactIntegrityException("无法篡改不存在的制品: " + artifactId);
            }
            byte[] content = Files.readAllBytes(dataFile);
            byte[] tampered = new byte[content.length + 1];
            System.arraycopy(content, 0, tampered, 0, content.length);
            tampered[content.length] = (byte) 'X';
            Files.write(dataFile, tampered);
        } catch (IOException e) {
            throw new IllegalStateException("篡改制品失败: " + artifactId, e);
        }
    }

    private Artifact readArtifact(String artifactId, String service, String version,
                                  String contentKey, byte[] content, boolean reused) {
        return new Artifact(artifactId, HashUtil.sha256Hex(content), contentKey,
                service, version, content, reused);
    }

    /** 制品字节内容：不含时间戳/随机数，保证同输入字节级一致。 */
    private byte[] deterministicContent(String service, String version, String contentKey) {
        String body = "LOCAL-ARTIFACT\nservice=" + service + "\nversion=" + version
                + "\ncontentKey=" + contentKey + "\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** 制品元数据 JSON。 */
    public record ArtifactMeta(String artifactId, String checksum, String service,
                               String version, String contentKey, int size) {
    }
}
