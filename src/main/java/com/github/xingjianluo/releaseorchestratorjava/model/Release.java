package com.github.xingjianluo.releaseorchestratorjava.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** 一次发布的聚合根（持久化为 JSON）。 */
public class Release {

    private String releaseId;
    private String serviceName;
    private String commit;
    private String contentHash;
    private String idempotencyKey;

    /** BUILD 成功后填充。 */
    private String version;
    private String artifactUri;
    private String artifactSha256;
    private long artifactSize;

    /** 提交时的源内容快照（本地状态自包含，重启后可重新物化制品）。 */
    private String sourceContent;
    /** 故障注入标记（如 fail:CANARY），不参与版本/制品计算。 */
    private String faultInjection;

    private ReleaseStatus status = ReleaseStatus.PENDING;
    private Map<StageName, StageState> stages = new EnumMap<>(StageName.class);

    private StageName failureStage;
    private String failureCode;
    private String failureReason;

    private String rolledBackFromVersion;
    private String rolledBackToVersion;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public Release() {
        for (StageName stage : StageName.ordered()) {
            stages.put(stage, new StageState(stage));
        }
    }

    public StageState stage(StageName name) {
        return stages.get(name);
    }

    /**
     * Jackson 反序列化 Map 时可能给出非 EnumMap 实现或缺键，
     * 统一归一化：键按枚举顺序重建，确保任意阶段都可取到非 null 状态。
     */
    public void setStages(Map<StageName, StageState> source) {
        EnumMap<StageName, StageState> normalized = new EnumMap<>(StageName.class);
        for (StageName name : StageName.ordered()) {
            StageState state = source == null ? null : source.get(name);
            if (state == null) {
                state = new StageState(name);
            } else {
                state.setName(name);
            }
            normalized.put(name, state);
        }
        this.stages = normalized;
    }

    /** 下一个待执行阶段；全部通过时返回 null。 */
    public StageName nextStage() {
        for (StageName name : StageName.ordered()) {
            if (stages.get(name).getStatus() != StageStatus.PASSED) {
                return name;
            }
        }
        return null;
    }

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getCommit() { return commit; }
    public void setCommit(String commit) { this.commit = commit; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getArtifactUri() { return artifactUri; }
    public void setArtifactUri(String artifactUri) { this.artifactUri = artifactUri; }
    public String getArtifactSha256() { return artifactSha256; }
    public void setArtifactSha256(String artifactSha256) { this.artifactSha256 = artifactSha256; }
    public long getArtifactSize() { return artifactSize; }
    public void setArtifactSize(long artifactSize) { this.artifactSize = artifactSize; }
    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String sourceContent) { this.sourceContent = sourceContent; }
    public String getFaultInjection() { return faultInjection; }
    public void setFaultInjection(String faultInjection) { this.faultInjection = faultInjection; }
    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }
    public Map<StageName, StageState> getStages() { return stages; }
    public StageName getFailureStage() { return failureStage; }
    public void setFailureStage(StageName failureStage) { this.failureStage = failureStage; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getRolledBackFromVersion() { return rolledBackFromVersion; }
    public void setRolledBackFromVersion(String v) { this.rolledBackFromVersion = v; }
    public String getRolledBackToVersion() { return rolledBackToVersion; }
    public void setRolledBackToVersion(String v) { this.rolledBackToVersion = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
