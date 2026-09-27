package com.github.xingjianluo.releaseorchestratorjava.group.model;

import java.util.List;

/**
 * 一次发布组提交：组内包含一个或多个服务的发布输入。
 *
 * @param idempotencyKey 组级幂等键；相同键重复提交被拒绝（DUPLICATE_SUBMISSION），
 *                       重复触发应复用既有组而不是重新提交。
 */
public record ReleaseGroupRequest(
        String idempotencyKey,
        List<GroupMemberRequest> members) {
}
