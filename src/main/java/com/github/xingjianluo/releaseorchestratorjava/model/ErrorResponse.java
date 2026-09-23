package com.github.xingjianluo.releaseorchestratorjava.model;

import java.time.Instant;

/** 可区分的错误原因：code 为稳定错误码，message 为人类可读说明。 */
public record ErrorResponse(
        Instant timestamp,
        String releaseId,
        String code,
        String message) {
}
