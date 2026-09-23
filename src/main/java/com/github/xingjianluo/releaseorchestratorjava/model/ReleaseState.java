package com.github.xingjianluo.releaseorchestratorjava.model;

/** 发布状态机中的状态。 */
public enum ReleaseState {
    PENDING,
    IN_PROGRESS,
    STABLE,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK
}
