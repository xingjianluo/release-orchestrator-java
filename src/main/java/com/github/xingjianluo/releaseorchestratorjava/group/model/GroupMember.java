package com.github.xingjianluo.releaseorchestratorjava.group.model;

import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;

/**
 * 发布组内一个服务成员的身份与进度快照。每个成员拥有独立 releaseId，
 * 状态以对应 Release 为准，这里保存的是便于查询/重启修复的快照。
 */
public class GroupMember {

    /** 组内序号（从 0 开始），决定成员在组内的稳定身份。 */
    private int index;
    private String serviceName;
    private String commit;
    private String contentHash;
    /** 该成员独立的发布身份；同组内相同输入的成员也互不相同。 */
    private String releaseId;
    private String version;
    /** 提交输入快照：进程在组元数据落盘后、成员 Release 落盘前被 kill 时，重启可据此补齐。 */
    private String sourceContent;
    private String faultInjection;
    private ReleaseStatus status = ReleaseStatus.PENDING;

    public GroupMember() {
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getCommit() { return commit; }
    public void setCommit(String commit) { this.commit = commit; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String sourceContent) { this.sourceContent = sourceContent; }
    public String getFaultInjection() { return faultInjection; }
    public void setFaultInjection(String faultInjection) { this.faultInjection = faultInjection; }
    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }
}
