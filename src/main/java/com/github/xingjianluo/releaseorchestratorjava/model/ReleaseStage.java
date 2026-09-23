package com.github.xingjianluo.releaseorchestratorjava.model;

/** 发布阶段，顺序即推进顺序。 */
public enum ReleaseStage {
    BUILD,
    STAGING,
    CANARY,
    FULL
}
