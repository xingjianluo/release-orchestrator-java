package com.github.xingjianluo.releaseorchestratorjava.model;

import com.github.xingjianluo.releaseorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.releaseorchestratorjava.error.InvalidStageTransitionException;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 发布记录（聚合根）：持有发布状态机。
 *
 * <p>状态机：</p>
 * <pre>
 * PENDING --BUILD 成功--> IN_PROGRESS --逐阶段通过--> STABLE
 *   任意阶段失败 --------> FAILED --回滚开始--> ROLLING_BACK --回滚完成--> ROLLED_BACK
 * </pre>
 * <p>已完成阶段保存在 {@code completedStages}（持久化），因此重复推进不会重复部署；
 * 崩溃恢复时未进入终态的 IN_PROGRESS / ROLLING_BACK 记录会被解释为 FAILED/ROLLED_BACK。</p>
 */
public class ReleaseRecord {

    private String releaseId;
    private String service;
    private String version;
    private String artifactId;
    private String artifactChecksum;
    private String sourceRef;
    private List<String> changeNotes = new ArrayList<>();
    private String failAtStage;

    private ReleaseState state = ReleaseState.PENDING;
    private List<ReleaseStage> completedStages = new ArrayList<>();

    private ReleaseStage failureStage;
    private String failureReasonCode;
    private String failureReason;

    /** 回滚目标（被回滚到的发布 id），仅在 ROLLING_BACK/ROLLED_BACK 时有值。 */
    private String rollbackTargetReleaseId;

    private Instant createdAt;
    private Instant updatedAt;

    public static ReleaseRecord start(String releaseId, ReleaseRequest request, String version,
                                      String artifactId, String artifactChecksum, Instant now) {
        ReleaseRecord r = new ReleaseRecord();
        r.releaseId = releaseId;
        r.service = request.getService();
        r.version = version;
        r.artifactId = artifactId;
        r.artifactChecksum = artifactChecksum;
        r.sourceRef = request.getSourceRef();
        r.changeNotes = new ArrayList<>(request.getChangeNotes());
        r.failAtStage = request.getFailAtStage();
        r.state = ReleaseState.PENDING;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    /** BUILD 成功：PENDING -> IN_PROGRESS，记录 BUILD 阶段。 */
    public void markBuildSucceeded(Instant now) {
        if (state != ReleaseState.PENDING) {
            throw new InvalidStageTransitionException(
                    "发布 " + releaseId + " 不处于 PENDING，无法进入构建后状态，当前状态=" + state);
        }
        state = ReleaseState.IN_PROGRESS;
        recordStage(ReleaseStage.BUILD, now);
    }

    /** 阶段推进成功：记录该阶段；若 FULL 也通过则进入 STABLE 终态。BUILD 走专用流转。 */
    public void markStageSucceeded(ReleaseStage stage, Instant now) {
        if (stage == ReleaseStage.BUILD) {
            markBuildSucceeded(now);
            return;
        }
        if (state != ReleaseState.IN_PROGRESS) {
            throw new InvalidStageTransitionException(
                    "发布 " + releaseId + " 不是进行中状态，不能推进阶段，当前状态=" + state);
        }
        recordStage(stage, now);
        if (stage == ReleaseStage.FULL) {
            state = ReleaseState.STABLE;
        }
        updatedAt = now;
    }

    private void recordStage(ReleaseStage stage, Instant now) {
        ReleaseStage expected = nextStage();
        if (stage != expected) {
            throw new InvalidStageTransitionException(
                    "发布 " + releaseId + " 的非法阶段流转：期望 " + expected + "，实际 " + stage);
        }
        completedStages.add(stage);
        updatedAt = now;
    }

    /** 任意阶段失败：进入 FAILED 终态，并保留可解释的失败原因。 */
    public void fail(ReleaseStage stage, ErrorCode code, String reason, Instant now) {
        state = ReleaseState.FAILED;
        failureStage = stage;
        failureReasonCode = code.name();
        failureReason = reason;
        updatedAt = now;
    }

    public void markRollingBack(String targetReleaseId, Instant now) {
        if (state != ReleaseState.FAILED) {
            throw new InvalidStageTransitionException(
                    "只有 FAILED 的发布可以开始回滚，发布 " + releaseId + " 当前状态=" + state);
        }
        state = ReleaseState.ROLLING_BACK;
        rollbackTargetReleaseId = targetReleaseId;
        updatedAt = now;
    }

    public void markRolledBack(Instant now) {
        if (state != ReleaseState.ROLLING_BACK) {
            throw new InvalidStageTransitionException(
                    "发布 " + releaseId + " 不在回滚中，无法标记回滚完成，当前状态=" + state);
        }
        state = ReleaseState.ROLLED_BACK;
        updatedAt = now;
    }

    /** 崩溃恢复：提交记录已落盘但 BUILD 结果未知，解释为 FAILED（BUILD 阶段失败）。 */
    public void recoverPendingAsFailed(Instant now) {
        state = ReleaseState.FAILED;
        failureStage = ReleaseStage.BUILD;
        failureReasonCode = ErrorCode.INVALID_STAGE_TRANSITION.name();
        failureReason = "进程中断恢复：BUILD 阶段结果未知，发布被标记为失败，可执行回滚";
        updatedAt = now;
    }

    /** 崩溃恢复：停留在 ROLLING_BACK 视为回滚已完成（回滚动作先于状态落盘）。 */
    public void recoverAsRolledBack(Instant now) {
        state = ReleaseState.ROLLED_BACK;
        updatedAt = now;
    }

    /** 崩溃恢复：停留在 IN_PROGRESS 的发布无法确认现场，解释为 FAILED。 */
    public void recoverAsFailed(Instant now) {
        state = ReleaseState.FAILED;
        failureStage = nextStage();
        failureReasonCode = ErrorCode.INVALID_STAGE_TRANSITION.name();
        failureReason = "进程中断恢复：阶段 " + failureStage + " 的部署结果未知，发布被标记为失败，可执行回滚";
        updatedAt = now;
    }

    /** 下一个待完成阶段；全部完成返回 null。 */
    public ReleaseStage nextStage() {
        for (ReleaseStage s : ReleaseStage.values()) {
            if (!completedStages.contains(s)) {
                return s;
            }
        }
        return null;
    }

    @JsonIgnore
    public boolean isStageCompleted(ReleaseStage stage) {
        return completedStages.contains(stage);
    }

    @JsonIgnore
    public boolean isAdvanceable() {
        return state == ReleaseState.IN_PROGRESS;
    }

    @JsonIgnore
    public boolean isTerminal() {
        Set<ReleaseState> terminal = EnumSet.of(
                ReleaseState.STABLE, ReleaseState.FAILED, ReleaseState.ROLLED_BACK);
        return terminal.contains(state);
    }

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }

    public String getArtifactChecksum() { return artifactChecksum; }
    public void setArtifactChecksum(String artifactChecksum) { this.artifactChecksum = artifactChecksum; }

    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }

    public List<String> getChangeNotes() { return changeNotes; }
    public void setChangeNotes(List<String> changeNotes) {
        this.changeNotes = changeNotes == null ? new ArrayList<>() : changeNotes;
    }

    public String getFailAtStage() { return failAtStage; }
    public void setFailAtStage(String failAtStage) { this.failAtStage = failAtStage; }

    public ReleaseState getState() { return state; }
    public void setState(ReleaseState state) { this.state = state; }

    public List<ReleaseStage> getCompletedStages() { return completedStages; }
    public void setCompletedStages(List<ReleaseStage> completedStages) {
        this.completedStages = completedStages == null ? new ArrayList<>() : completedStages;
    }

    public ReleaseStage getFailureStage() { return failureStage; }
    public void setFailureStage(ReleaseStage failureStage) { this.failureStage = failureStage; }

    public String getFailureReasonCode() { return failureReasonCode; }
    public void setFailureReasonCode(String failureReasonCode) { this.failureReasonCode = failureReasonCode; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getRollbackTargetReleaseId() { return rollbackTargetReleaseId; }
    public void setRollbackTargetReleaseId(String rollbackTargetReleaseId) {
        this.rollbackTargetReleaseId = rollbackTargetReleaseId;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
