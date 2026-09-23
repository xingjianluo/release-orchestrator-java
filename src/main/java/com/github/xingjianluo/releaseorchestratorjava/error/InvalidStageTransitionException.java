package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：InvalidStageTransitionException。 */
public class InvalidStageTransitionException extends ReleaseException {
    public InvalidStageTransitionException(String message) {
        super(ErrorCode.INVALID_STAGE_TRANSITION, message);
    }
}
