package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：RollbackConflictException。 */
public class RollbackConflictException extends ReleaseException {
    public RollbackConflictException(String message) {
        super(ErrorCode.ROLLBACK_CONFLICT, message);
    }
}
