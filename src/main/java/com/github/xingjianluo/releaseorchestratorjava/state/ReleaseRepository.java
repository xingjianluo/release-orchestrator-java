package com.github.xingjianluo.releaseorchestratorjava.state;

import com.github.xingjianluo.releaseorchestratorjava.model.Release;

import java.util.List;
import java.util.Optional;

/** 发布状态存储：按 releaseId 持久化，原子写入，重启后可完整恢复。 */
public interface ReleaseRepository {

    void save(Release release);

    Optional<Release> find(String releaseId);

    List<Release> findAll();

    /** 同 (serviceName,commit,contentHash) 是否已有发布。 */
    Optional<Release> findByContent(String serviceName, String commit, String contentHash);

    boolean lock(String releaseId, long timeoutMillis);

    void unlock(String releaseId);
}
