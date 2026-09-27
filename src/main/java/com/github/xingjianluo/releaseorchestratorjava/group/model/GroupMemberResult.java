package com.github.xingjianluo.releaseorchestratorjava.group.model;

import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;

/**
 * 组级操作（推进/回滚）作用于单个成员后的结果。
 *
 * @param advanced true 表示本次确实发生了一次阶段推进；false 表示该成员无需推进（终态/失败/回滚态跳过）
 * @param errorCode 该成员独立失败/被拒绝时的稳定错误码；成功或跳过时为 null。错误不影响其他成员。
 */
public record GroupMemberResult(
        int index,
        String serviceName,
        String releaseId,
        ReleaseStatus status,
        boolean advanced,
        String errorCode,
        String message) {
}
