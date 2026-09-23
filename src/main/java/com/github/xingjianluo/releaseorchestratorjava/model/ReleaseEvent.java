package com.github.xingjianluo.releaseorchestratorjava.model;

import java.time.Instant;

/** 不可变事件，进入仅追加事件日志。 */
public record ReleaseEvent(
        long seq,
        Instant at,
        String releaseId,
        String type,
        String detail) {
}
