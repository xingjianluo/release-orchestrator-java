package com.github.xingjianluo.releaseorchestratorjava.group.release;

import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberResult;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;

import java.util.List;

/**
 * 发布组编排应用服务：一次提交一组服务，组内每个服务各自走自己的阶段，
 * 故障隔离、独立回滚、互不顶掉。
 */
public interface ReleaseGroupOrchestrator {

    /** 提交发布组：为每个成员建立独立发布身份。 */
    ReleaseGroup submit(ReleaseGroupRequest request);

    /**
     * 并行推进组内所有可推进成员各一个阶段；
     * 失败/回滚终态成员跳过，其错误只记录在自己的结果中，不影响其他成员。
     */
    List<GroupMemberResult> advance(String groupId);

    /** 回滚组内所有处于 FAILED 的成员到各自上一个稳定版本；其余成员不受牵连。 */
    List<GroupMemberResult> rollback(String groupId);

    /** 回滚组内单个成员（按服务名定位）到它自己的上一个稳定版本。 */
    Release rollbackMember(String groupId, String serviceName);

    ReleaseGroup get(String groupId);

    List<ReleaseGroup> list();

    /**
     * 重启调和：先确保组元数据中每个成员的 Release 都存在并补齐，
     * 再依据各成员权威状态刷新组状态。返回被调和的组数。
     */
    int recoverInterrupted();
}
