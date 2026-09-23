package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.model.StageName;

/**
 * 健康准入探针（本地确定性模拟）。
 * 相同输入对相同阶段必须给出相同结论，便于离线复现。
 */
public interface HealthProbe {

    boolean passes(String serviceName, String version, StageName stage, String faultSpec);

    String describe(String serviceName, String version, StageName stage, String faultSpec);
}
