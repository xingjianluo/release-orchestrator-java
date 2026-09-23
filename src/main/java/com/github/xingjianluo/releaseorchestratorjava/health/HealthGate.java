package com.github.xingjianluo.releaseorchestratorjava.health;

import com.github.xingjianluo.releaseorchestratorjava.artifact.Artifact;
import com.github.xingjianluo.releaseorchestratorjava.error.HealthCheckFailureException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 确定性健康准入：无任何随机或网络因素。
 *
 * <p>检查规则：发布请求携带的 {@code failAtStage}（本地故障注入钩子）与当前阶段同名时失败，
 * 否则通过。相同输入 + 相同阶段的检查结论永远一致，因此测试可离线精确复现失败链路。</p>
 */
@Component
public class HealthGate {

    private static final Logger log = LoggerFactory.getLogger(HealthGate.class);

    public void check(ReleaseStage stage, Artifact artifact, String failAtStage) {
        log.info("健康准入检查 stage={} artifactId={} 注入失败阶段={}", stage,
                artifact == null ? null : artifact.getArtifactId(), failAtStage);
        if (failAtStage != null && failAtStage.equalsIgnoreCase(stage.name())) {
            throw new HealthCheckFailureException(
                    "阶段 " + stage + " 健康检查未通过（确定性故障注入）：制品 "
                            + (artifact == null ? null : artifact.getArtifactId()) + " 未达到准入标准");
        }
    }
}
