package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.model.GroupOperationResult;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupView;

import java.util.List;

/**
 * 发布组应用服务：一次把一组服务编排出去。
 * 组内每个服务走自己的发布身份与阶段进度，独立推进、独立失败、独立回滚；
 * 组级批量操作只是“逐成员执行 + 逐成员结论”，不做跨成员的状态耦合。
 */
public interface ReleaseGroupOrchestrator {

    /** 提交发布组；相同成员集合重复提交幂等返回既有组。 */
    ReleaseGroupView submitGroup(ReleaseGroupRequest request);

    /** 组内每个可推进成员各自推进一个阶段；单个成员失败不影响其余成员。 */
    GroupOperationResult advanceGroup(String groupId);

    /** 组内每个可回滚成员各自回滚到自己的上一个稳定版本，互不牵连。 */
    GroupOperationResult rollbackGroup(String groupId);

    ReleaseGroupView getGroup(String groupId);

    List<ReleaseGroupView> listGroups();
}
