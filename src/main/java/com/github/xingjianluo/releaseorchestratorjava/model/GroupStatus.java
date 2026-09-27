package com.github.xingjianluo.releaseorchestratorjava.model;

/**
 * 发布组派生状态（由成员发布状态实时推导，不单独持久化）：
 * <pre>
 * PENDING      全部成员 PENDING
 * IN_PROGRESS  至少一个成员仍在推进（PENDING/IN_PROGRESS），且无成员处于回滚中
 * ROLLING_BACK 至少一个成员正在回滚
 * COMPLETED    全部成员 COMPLETED
 * FAILED       无成员在推进，且至少一个 FAILED（其余可能 COMPLETED）
 * ROLLED_BACK  全部成员到达 ROLLED_BACK
 * PARTIAL      其余混合终态（如部分 ROLLED_BACK、部分 COMPLETED）
 * </pre>
 */
public enum GroupStatus {
    PENDING,
    IN_PROGRESS,
    ROLLING_BACK,
    COMPLETED,
    FAILED,
    ROLLED_BACK,
    PARTIAL
}
