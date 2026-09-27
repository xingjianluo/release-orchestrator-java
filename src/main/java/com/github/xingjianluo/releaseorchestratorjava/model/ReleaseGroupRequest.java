package com.github.xingjianluo.releaseorchestratorjava.model;

import java.util.List;

/**
 * 发布组提交输入：一次携带多个服务（各自提交与内容）。
 *
 * @param groupKey 调用方可选的幂等键（如工单号），仅记录与展示，不参与 groupId 派生
 * @param members  组内成员；每个成员就是一条独立的单服务发布输入，
 *                 服务名必须在组内唯一
 */
public record ReleaseGroupRequest(
        String groupKey,
        List<ReleaseRequest> members) {
}
