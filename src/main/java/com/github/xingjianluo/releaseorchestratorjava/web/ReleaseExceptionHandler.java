package com.github.xingjianluo.releaseorchestratorjava.web;

import com.github.xingjianluo.releaseorchestratorjava.model.ErrorResponse;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Set;

/** 把稳定错误码映射为可区分的 HTTP 响应，body 始终携带 code 与 message。 */
@RestControllerAdvice
public class ReleaseExceptionHandler {

    private static final Set<String> NOT_FOUND = Set.of(
            "RELEASE_NOT_FOUND",
            "GROUP_NOT_FOUND",
            "GROUP_MEMBER_NOT_FOUND");

    private static final Set<String> CONFLICT = Set.of(
            "RELEASE_BUSY",
            "DUPLICATE_SUBMISSION",
            "DUPLICATE_SERVICE_IN_GROUP",
            "RELEASE_FAILED",
            "RELEASE_ROLLING_BACK",
            "RELEASE_ROLLED_BACK",
            "RELEASE_ALREADY_COMPLETED",
            "STAGE_ORDER_VIOLATION",
            "ROLLBACK_NOT_ALLOWED",
            "ARTIFACT_CONTENT_CONFLICT");

    @ExceptionHandler(ReleaseException.class)
    public ResponseEntity<ErrorResponse> handle(ReleaseException ex, HttpServletRequest request) {
        HttpStatus status = NOT_FOUND.contains(ex.getCode()) ? HttpStatus.NOT_FOUND
                : CONFLICT.contains(ex.getCode()) ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                extractResourceId(request.getRequestURI()),
                ex.getCode(),
                ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    /** 从 /releases/{id} 或 /release-groups/{id} 路径里提取资源 id，仅用于错误响应展示。 */
    private String extractResourceId(String uri) {
        String marker = null;
        int at = -1;
        int releaseMarker = uri.indexOf("/releases/");
        int groupMarker = uri.indexOf("/release-groups/");
        if (releaseMarker >= 0) {
            marker = "/releases/";
            at = releaseMarker;
        } else if (groupMarker >= 0) {
            marker = "/release-groups/";
            at = groupMarker;
        }
        if (marker == null) {
            return null;
        }
        String tail = uri.substring(at + marker.length());
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }
}
