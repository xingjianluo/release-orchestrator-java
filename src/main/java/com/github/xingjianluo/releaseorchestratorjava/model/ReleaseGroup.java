package com.github.xingjianluo.releaseorchestratorjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布组聚合根（持久化为 JSON）。组本身只记录成员身份，
 * 阶段进度与成败终态全部落在成员各自的 Release 上，
 * 组状态由成员状态实时推导，因此进程中断后不会出现组与成员不一致。
 */
public class ReleaseGroup {

    private String groupId;
    private String groupKey;
    private List<Member> members = new ArrayList<>();
    private Instant createdAt;

    /** 组成员：服务名 + 该服务自己的发布身份。 */
    public record Member(String serviceName, String releaseId) {
    }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public List<Member> getMembers() { return members; }
    public void setMembers(List<Member> members) { this.members = members; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
