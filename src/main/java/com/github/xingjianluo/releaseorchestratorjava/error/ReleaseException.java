package com.github.xingjianluo.releaseorchestratorjava.error;

/** 所有发布领域错误的基类，携带可区分的原因码。 */
public class ReleaseException extends RuntimeException {

    private final ErrorCode errorCode;

    public ReleaseException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ReleaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
