package com.github.xingjianluo.releaseorchestratorjava.release;

/**
 * 确定性发布身份规则。
 * <p>releaseId = {@code rel-<serviceName>-<commit前7位>-<contentHash前12位>}。
 * <p>把 serviceName 放进身份里，是“单仓多服务”隔离的关键：
 * 即使两个服务（无论单服务提交还是同组多服务）使用完全相同的 commit 与源内容，
 * 它们的 releaseId 也互不相同，各自有独立状态文件、锁、阶段进度与部署历史，
 * 不会互相顶掉，也不会被当成同一条发布。
 */
public final class ReleaseIdentity {

    private ReleaseIdentity() {
    }

    public static String releaseId(String serviceName, String commit, String contentHash) {
        return "rel-" + serviceName + "-" + commit.substring(0, 7) + "-" + contentHash.substring(0, 12);
    }
}
