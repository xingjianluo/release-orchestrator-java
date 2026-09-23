package com.github.xingjianluo.releaseorchestratorjava.artifact;

import com.github.xingjianluo.releaseorchestratorjava.model.ArtifactCoordinates;

/** 本地制品仓库：确定性生成、原子落盘、完整性校验、禁止覆盖。 */
public interface ArtifactStore {

    /** 若相同内容制品已存在则复用；不得静默覆盖内容不一致的已有制品。 */
    ArtifactCoordinates materialize(String serviceName, String version, byte[] content);

    /** 按坐标重新计算摘要并比对，失败抛 ARTIFACT_CHECKSUM_MISMATCH。 */
    void verify(ArtifactCoordinates coordinates);
}
