package com.github.xingjianluo.releaseorchestratorjava.version;

import com.github.xingjianluo.releaseorchestratorjava.error.InvalidVersionException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 确定性版本与发布标识生成器。
 *
 * <ul>
 *   <li>版本必须是语义版本 {@code MAJOR.MINOR.PATCH}（可选 -预发布标识），否则以
 *       {@code INVALID_VERSION} 拒绝；</li>
 *   <li>发布标识 rel-&lt;16位哈希&gt; 与制品内容键仅由 service/version/sourceRef/changeNotes
 *       规范化后派生：相同输入一定得到相同标识；{@code failAtStage} 不参与；</li>
 *   <li>版本号本身由调用方显式声明（声明式版本），非法流转（降级/重复）由编排层校验拒绝。</li>
 * </ul>
 */
@Component
public class VersionGenerator {

    /** 语义版本（MAJOR.MINOR.PATCH，可选连字符预发布后缀）。 */
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?$");

    public String resolveVersion(ReleaseRequest request) {
        validate(request);
        return request.getVersion().trim();
    }

    /** 确定性发布标识：rel- + 内容键的前 16 位 SHA-256。 */
    public String releaseId(ReleaseRequest request) {
        return "rel-" + HashUtil.sha256Hex(contentKey(request)).substring(0, 16);
    }

    /** 制品内容键：制品字节内容由此确定性派生（另含构建时间无关性保证）。 */
    public String contentKey(ReleaseRequest request) {
        validate(request);
        List<String> notes = request.getChangeNotes() == null
                ? List.of()
                : request.getChangeNotes().stream().sorted().collect(Collectors.toList());
        return "service=" + request.getService().trim()
                + "|version=" + request.getVersion().trim()
                + "|sourceRef=" + (request.getSourceRef() == null ? "" : request.getSourceRef().trim())
                + "|notes=" + String.join(";", notes);
    }

    private void validate(ReleaseRequest request) {
        if (request == null) {
            throw new InvalidVersionException("发布请求不能为空");
        }
        if (request.getService() == null || request.getService().isBlank()) {
            throw new InvalidVersionException("服务名不能为空");
        }
        String v = request.getVersion();
        if (v == null || v.isBlank()) {
            throw new InvalidVersionException("版本号不能为空");
        }
        if (!SEMVER.matcher(v.trim()).matches()) {
            throw new InvalidVersionException(
                    "非法版本号 " + v + "：必须符合语义版本 MAJOR.MINOR.PATCH[-预发布标识]");
        }
    }
}
