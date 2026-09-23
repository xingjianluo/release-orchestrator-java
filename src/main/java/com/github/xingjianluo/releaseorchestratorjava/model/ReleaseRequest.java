package com.github.xingjianluo.releaseorchestratorjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 发布请求输入。
 *
 * <p>版本与制品标识仅由 service / version / sourceRef / changeNotes 确定性派生；
 * {@code failAtStage} 是本地故障注入钩子（取值为阶段名，如 CANARY），不参与任何标识计算，
 * 因此“健康结果不同”不会改变版本与制品的确定性与复用性。</p>
 */
public class ReleaseRequest {

    /** 服务名，必填。 */
    private String service;

    /** 请求发布的语义版本（SemVer），如 1.2.0，必填且需通过格式与流转校验。 */
    private String version;

    /** 确定性源码引用（如内容哈希、tag），参与制品内容派生。 */
    private String sourceRef;

    /** 变更说明，参与制品内容派生。 */
    private List<String> changeNotes = new ArrayList<>();

    /** 故障注入钩子：在指定阶段健康检查失败；为空表示全部通过。不参与标识计算。 */
    private String failAtStage;

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public List<String> getChangeNotes() {
        return changeNotes;
    }

    public void setChangeNotes(List<String> changeNotes) {
        this.changeNotes = changeNotes == null ? new ArrayList<>() : changeNotes;
    }

    public String getFailAtStage() {
        return failAtStage;
    }

    public void setFailAtStage(String failAtStage) {
        this.failAtStage = failAtStage;
    }
}
