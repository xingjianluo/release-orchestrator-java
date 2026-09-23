package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：DuplicateReleaseException。 */
public class DuplicateReleaseException extends ReleaseException {
    public DuplicateReleaseException(String message) {
        super(ErrorCode.DUPLICATE_RELEASE, message);
    }
}
