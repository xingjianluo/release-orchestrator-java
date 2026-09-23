package com.github.xingjianluo.releaseorchestratorjava.model;

import java.time.Instant;

/** 单个阶段的可变状态（随发布状态一起持久化）。 */
public class StageState {

    private StageName name;
    private StageStatus status = StageStatus.PENDING;
    private int attempts = 0;
    private Instant startedAt;
    private Instant finishedAt;
    private String failureReason;
    private String failureCode;

    public StageState() {
    }

    public StageState(StageName name) {
        this.name = name;
    }

    public StageName getName() {
        return name;
    }

    public void setName(StageName name) {
        this.name = name;
    }

    public StageStatus getStatus() {
        return status;
    }

    public void setStatus(StageStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }
}
