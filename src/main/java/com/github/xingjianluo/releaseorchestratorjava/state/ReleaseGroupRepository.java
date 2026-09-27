package com.github.xingjianluo.releaseorchestratorjava.state;

import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroup;

import java.util.List;
import java.util.Optional;

/** 发布组存储：按 groupId 持久化，原子写入，重启后可完整恢复。 */
public interface ReleaseGroupRepository {

    void save(ReleaseGroup group);

    Optional<ReleaseGroup> find(String groupId);

    List<ReleaseGroup> findAll();

    boolean lock(String groupId, long timeoutMillis);

    void unlock(String groupId);
}
