package com.github.xingjianluo.releaseorchestratorjava.group.model;

/**
 * 发布组聚合状态（由组内各成员状态派生，不代表单个服务的发布结论）：
 * <pre>
 * PENDING       —— 尚未有任何成员推进
 * IN_PROGRESS   —— 至少一个成员仍在推进，且尚无成员失败
 * DEGRADED      —— 已有成员进入失败/回滚终态，但其余成员仍在继续推进
 * COMPLETED     —— 所有成员都 COMPLETED
 * COMPLETED_WITH_FAILURES —— 所有成员均到终态，且至少一个成员 FAILED/ROLLED_BACK
 * </pre>
 */
public enum GroupStatus {
    PENDING,
    IN_PROGRESS,
    DEGRADED,
    COMPLETED,
    COMPLETED_WITH_FAILURES
}
