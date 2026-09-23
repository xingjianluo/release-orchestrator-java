package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：ReleaseNotAdvanceableException。 */
public class ReleaseNotAdvanceableException extends ReleaseException {
    public ReleaseNotAdvanceableException(String message) {
        super(ErrorCode.RELEASE_NOT_ADVANCEABLE, message);
    }
}
