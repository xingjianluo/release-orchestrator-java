package com.github.xingjianluo.releaseorchestratorjava.model;

import java.time.Instant;

/** 服务维度的 append-only 变更历史条目，保证已发布版本与变更历史不丢失。 */
public class HistoryEntry {

    private long seq;
    private Instant timestamp;
    private String releaseId;
    private String event;
    private String detail;

    public HistoryEntry() {
    }

    public HistoryEntry(long seq, Instant timestamp, String releaseId, String event, String detail) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.releaseId = releaseId;
        this.event = event;
        this.detail = detail;
    }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
