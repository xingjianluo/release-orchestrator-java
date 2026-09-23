package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：ReleaseNotFoundException。 */
public class ReleaseNotFoundException extends ReleaseException {
    public ReleaseNotFoundException(String message) {
        super(ErrorCode.RELEASE_NOT_FOUND, message);
    }
}
