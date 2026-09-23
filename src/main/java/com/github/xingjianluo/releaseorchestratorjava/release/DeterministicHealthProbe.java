package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 确定性健康准入探针，完全离线可复现。
 * <p>faultSpec 为逗号分隔的令牌，取值：
 * <ul>
 *   <li>{@code fail:BUILD} / {@code fail:STAGING} / {@code fail:CANARY} / {@code fail:PRODUCTION}
 *       —— 指定阶段健康检查必然失败；</li>
 *   <li>{@code fail} —— 所有阶段都失败（BUILD 以构建校验失败表达）。</li>
 * </ul>
 * 相同 (version, stage, faultSpec) 永远得到相同结论，不依赖时间或随机量。
 */
@Component
public class DeterministicHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(DeterministicHealthProbe.class);

    @Override
    public boolean passes(String serviceName, String version, StageName stage, String faultSpec) {
        boolean result = !matches(stage, faultSpec);
        log.debug("health probe service={} version={} stage={} faultSpec='{}' -> {}",
                serviceName, version, stage, faultSpec, result ? "PASS" : "FAIL");
        return result;
    }

    @Override
    public String describe(String serviceName, String version, StageName stage, String faultSpec) {
        boolean pass = passes(serviceName, version, stage, faultSpec);
        if (pass) {
            return "health check passed at stage " + stage + " (deterministic probe)";
        }
        return "deterministic health check failed at stage " + stage
                + " (faultSpec=" + faultSpec + ")";
    }

    private boolean matches(StageName stage, String faultSpec) {
        if (faultSpec == null || faultSpec.isBlank()) {
            return false;
        }
        for (String token : faultSpec.split(",")) {
            String t = token.trim();
            if (t.equalsIgnoreCase("fail")) {
                return true;
            }
            if (t.equalsIgnoreCase("fail:" + stage.name())) {
                return true;
            }
        }
        return false;
    }
}
