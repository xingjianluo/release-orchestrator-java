package com.github.xingjianluo.releaseorchestratorjava.version;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 默认确定性版本/制品计算器：
 * <ul>
 *   <li>contentHash = sha256(sourceContent)；</li>
 *   <li>version    = 1.0.0-&lt;commit 前7位&gt;+&lt;contentHash 前12位&gt;；</li>
 *   <li>artifact 字节 = 固定格式清单头 + 原始 sourceContent，无时间戳/随机量，
 *       因此相同输入必然得到相同字节与相同制品 SHA-256。</li>
 * </ul>
 */
@Component
public class DefaultVersionCalculator implements VersionCalculator {

    static final String ARTIFACT_HEADER = "RELEASE-ARTIFACT/1";

    @Override
    public String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String calculateVersion(String serviceName, String commit, String sourceContent) {
        String contentHash = sha256Hex(sourceContent);
        return "1.0.0-" + commit.substring(0, 7) + "+" + contentHash.substring(0, 12);
    }

    @Override
    public byte[] buildArtifactBytes(String serviceName, String commit, String sourceContent, String version) {
        String contentHash = sha256Hex(sourceContent);
        String manifest = ARTIFACT_HEADER + "\n"
                + "service=" + serviceName + "\n"
                + "commit=" + commit + "\n"
                + "version=" + version + "\n"
                + "content-sha256=" + contentHash + "\n"
                + "\n";
        byte[] head = manifest.getBytes(StandardCharsets.UTF_8);
        byte[] body = sourceContent.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    /** 字节数组的完整 SHA-256 十六进制（小写）。 */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
