package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：HealthCheckFailureException。 */
public class HealthCheckFailureException extends ReleaseException {
    public HealthCheckFailureException(String message) {
        super(ErrorCode.HEALTH_CHECK_FAILED, message);
    }
}
