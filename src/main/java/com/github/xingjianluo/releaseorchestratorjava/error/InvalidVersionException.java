package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：InvalidVersionException。 */
public class InvalidVersionException extends ReleaseException {
    public InvalidVersionException(String message) {
        super(ErrorCode.INVALID_VERSION, message);
    }
}
