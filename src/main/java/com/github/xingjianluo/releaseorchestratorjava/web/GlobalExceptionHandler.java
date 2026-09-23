package com.github.xingjianluo.releaseorchestratorjava.web;

import com.github.xingjianluo.releaseorchestratorjava.error.ReleaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误响应：HTTP 状态 + 可区分原因码 + 可读原因。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ReleaseException.class)
    public ResponseEntity<Map<String, String>> handle(ReleaseException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_RELEASE, INVALID_VERSION,
                 INVALID_STAGE_TRANSITION, RELEASE_NOT_ADVANCEABLE,
                 HEALTH_CHECK_FAILED, ROLLBACK_CONFLICT, ARTIFACT_INTEGRITY_FAILED -> HttpStatus.CONFLICT;
        };
        log.warn("请求被拒绝 httpStatus={} 原因码={} 原因={}", status, ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "errorCode", ex.getErrorCode().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
