package com.github.xingjianluo.releaseorchestratorjava.error;

/** 可区分的失败原因码。 */
public enum ErrorCode {
    INVALID_VERSION,
    DUPLICATE_RELEASE,
    ARTIFACT_INTEGRITY_FAILED,
    RELEASE_NOT_FOUND,
    INVALID_STAGE_TRANSITION,
    HEALTH_CHECK_FAILED,
    RELEASE_NOT_ADVANCEABLE,
    ROLLBACK_CONFLICT
}
