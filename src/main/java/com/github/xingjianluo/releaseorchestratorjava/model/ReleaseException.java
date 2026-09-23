package com.github.xingjianluo.releaseorchestratorjava.model;

/** 带稳定错误码的业务异常，错误码用于区分不同的拒绝/失败原因。 */
public class ReleaseException extends RuntimeException {

    private final String code;

    public ReleaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ReleaseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
