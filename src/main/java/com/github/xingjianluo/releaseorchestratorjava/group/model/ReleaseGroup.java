package com.github.xingjianluo.releaseorchestratorjava.group.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布组聚合根（持久化为 groups/{groupId}.json）。
 * 组的结论始终可由成员各自的 Release 状态重新派生；
 * 组状态快照只是查询便利，成员 Release 是唯一权威状态。
 */
public class ReleaseGroup {

    private String groupId;
    private String idempotencyKey;
    private GroupStatus status = GroupStatus.PENDING;
    private List<GroupMember> members = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public ReleaseGroup() {
    }

    public GroupMember member(int index) {
        return members.get(index);
    }

    public GroupMember findByReleaseId(String releaseId) {
        for (GroupMember member : members) {
            if (releaseId.equals(member.getReleaseId())) {
                return member;
            }
        }
        return null;
    }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public GroupStatus getStatus() { return status; }
    public void setStatus(GroupStatus status) { this.status = status; }
    public List<GroupMember> getMembers() { return members; }
    public void setMembers(List<GroupMember> members) {
        this.members = members == null ? new ArrayList<>() : members;
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
