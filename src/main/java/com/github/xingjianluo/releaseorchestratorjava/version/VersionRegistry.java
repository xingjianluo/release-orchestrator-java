package com.github.xingjianluo.releaseorchestratorjava.version;

import java.util.List;

/** 版本登记册：记录服务成功发布（COMPLETED）的稳定版本，用于回滚目标选择。 */
public interface VersionRegistry {

    /** 注册稳定版本；同版本重复注册幂等。 */
    void registerStable(String serviceName, String version);

    List<String> stableVersions(String serviceName);

    /** 上一个稳定版本；没有时返回 null。 */
    String previousStable(String serviceName, String currentVersion);
}
