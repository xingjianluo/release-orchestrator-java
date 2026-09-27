package com.github.xingjianluo.releaseorchestratorjava.model;

import java.util.List;

/** 组级批量操作（推进/回滚）的汇总结果：逐成员结论 + 操作后的组视图。 */
public record GroupOperationResult(
        String groupId,
        String operation,
        List<MemberOutcome> outcomes,
        ReleaseGroupView group) {
}
