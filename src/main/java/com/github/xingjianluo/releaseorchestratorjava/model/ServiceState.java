package com.github.xingjianluo.releaseorchestratorjava.model;

import com.github.xingjianluo.releaseorchestratorjava.error.RollbackConflictException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务状态：记录当前线上稳定版本、上一个稳定版本（回滚目标）以及进行中的发布。
 *
 * <p>不变式：</p>
 * <ul>
 *   <li>{@code activeReleaseId} 非空时表示该服务有发布在推进，禁止并发触发或回滚；</li>
 *   <li>发布 FULL 成功后：previous=旧 current（可能为 null），current=新发布；</li>
 *   <li>回滚成功后：current 与 previous 交换，历史不删除。</li>
 * </ul>
 */
public class ServiceState {

    private String service;
    private String currentReleaseId;
    private String currentVersion;
    private String previousReleaseId;
    private String previousVersion;
    private String activeReleaseId;
    /** 最近一次失败、等待回滚决策的发布 id（此时线上仍是上一个稳定版本）。 */
    private String pendingRollbackReleaseId;
    private Instant updatedAt;
    private List<HistoryEntry> history = new ArrayList<>();

    public static ServiceState empty(String service, Instant now) {
        ServiceState s = new ServiceState();
        s.service = service;
        s.updatedAt = now;
        return s;
    }

    /** 发布开始：占用服务，拒绝并发发布/回滚交叉执行。 */
    public void markActive(ReleaseRecord release, Instant now) {
        if (activeReleaseId != null && !activeReleaseId.equals(release.getReleaseId())) {
            throw new RollbackConflictException(
                    "服务 " + service + " 已有进行中的发布 " + activeReleaseId
                            + "，拒绝并发触发 " + release.getReleaseId());
        }
        activeReleaseId = release.getReleaseId();
        updatedAt = now;
    }

    /** 发布 FULL 成功：新稳定版本上线。 */
    public void markStable(ReleaseRecord release, Instant now) {
        previousReleaseId = currentReleaseId;
        previousVersion = currentVersion;
        currentReleaseId = release.getReleaseId();
        currentVersion = release.getVersion();
        activeReleaseId = null;
        updatedAt = now;
    }

    /** 发布失败：释放占用并登记待回滚发布，线上版本保持不变（仍是上一个稳定版本）。 */
    public void markFailed(ReleaseRecord release, Instant now) {
        if (release.getReleaseId().equals(activeReleaseId)) {
            activeReleaseId = null;
        }
        pendingRollbackReleaseId = release.getReleaseId();
        updatedAt = now;
    }

    /**
     * 执行回滚（调用方已保证无进行中发布）。
     * <ul>
     *   <li>存在失败待回滚发布：线上本来就仍是上一个稳定版本，只需确认恢复并清掉待回滚登记；</li>
     *   <li>否则针对已上线版本回滚：current 与 previous 交换。</li>
     * </ul>
     */
    public void applyRollback(Instant now) {
        if (pendingRollbackReleaseId != null) {
            pendingRollbackReleaseId = null;
            updatedAt = now;
            return;
        }
        if (previousReleaseId == null) {
            throw new RollbackConflictException(
                    "服务 " + service + " 没有可回滚的上一个稳定版本");
        }
        String newCurrentRelease = previousReleaseId;
        String newCurrentVersion = previousVersion;
        previousReleaseId = currentReleaseId;
        previousVersion = currentVersion;
        currentReleaseId = newCurrentRelease;
        currentVersion = newCurrentVersion;
        updatedAt = now;
    }

    public void appendHistory(HistoryEntry entry) {
        history.add(entry);
    }

    public long nextHistorySeq() {
        return history.isEmpty() ? 1 : history.get(history.size() - 1).getSeq() + 1;
    }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getCurrentReleaseId() { return currentReleaseId; }
    public void setCurrentReleaseId(String currentReleaseId) { this.currentReleaseId = currentReleaseId; }

    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }

    public String getPreviousReleaseId() { return previousReleaseId; }
    public void setPreviousReleaseId(String previousReleaseId) { this.previousReleaseId = previousReleaseId; }

    public String getPreviousVersion() { return previousVersion; }
    public void setPreviousVersion(String previousVersion) { this.previousVersion = previousVersion; }

    public String getActiveReleaseId() { return activeReleaseId; }
    public void setActiveReleaseId(String activeReleaseId) { this.activeReleaseId = activeReleaseId; }

    public String getPendingRollbackReleaseId() { return pendingRollbackReleaseId; }
    public void setPendingRollbackReleaseId(String pendingRollbackReleaseId) {
        this.pendingRollbackReleaseId = pendingRollbackReleaseId; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<HistoryEntry> getHistory() { return history; }
    public void setHistory(List<HistoryEntry> history) {
        this.history = history == null ? new ArrayList<>() : history;
    }
}
