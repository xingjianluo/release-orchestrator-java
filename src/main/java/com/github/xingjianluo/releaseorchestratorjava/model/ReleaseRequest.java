package com.github.xingjianluo.releaseorchestratorjava.model;

/**
 * 一次发布提交的输入。version 由 (serviceName, commit, sourceContent) 确定性派生，
 * 输入相同则版本与制品标识必然相同。
 *
 * @param faultInjection 故障注入标记（如 "fail:CANARY"），仅影响健康探针结论，
 *                       不参与版本/制品的确定性计算；为空表示全流程健康。
 */
public record ReleaseRequest(
        String serviceName,
        String commit,
        String sourceContent,
        String idempotencyKey,
        String faultInjection) {
}
