package com.github.xingjianluo.releaseorchestratorjava.model;

/**
 * 发布状态机：
 * <pre>
 * PENDING -> IN_PROGRESS -> COMPLETED
 *                 |             ^
 *                 v             |
 *              FAILED -----> ROLLED_BACK
 *                 ^
 *        (重启恢复 RUNNING 中状态)
 * 任意阶段失败：IN_PROGRESS -> FAILED
 * 回滚：FAILED/IN_PROGRESS -> ROLLING_BACK -> ROLLED_BACK
 * </pre>
 */
public enum ReleaseStatus {
    PENDING,
    IN_PROGRESS,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
    COMPLETED
}
