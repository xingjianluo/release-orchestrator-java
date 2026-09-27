package com.github.xingjianluo.releaseorchestratorjava.group.model;

/**
 * 发布组中单个服务的提交输入。每个成员各自携带自己的 commit / sourceContent /
 * faultInjection，即使两个成员的输入完全相同，也会各自成立为独立发布。
 */
public record GroupMemberRequest(
        String serviceName,
        String commit,
        String sourceContent,
        String faultInjection) {
}
