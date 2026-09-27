package com.github.xingjianluo.releaseorchestratorjava.group.state;

import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;

import java.util.List;
import java.util.Optional;

/** 发布组状态存储：按 groupId 持久化，原子写入，重启后可完整恢复。 */
public interface GroupRepository {

    void save(ReleaseGroup group);

    Optional<ReleaseGroup> find(String groupId);

    List<ReleaseGroup> findAll();

    /** 组级操作互斥锁（进程内锁 + JVM 文件锁），串行化同组的并发触发。 */
    boolean lock(String groupId, long timeoutMillis);

    void unlock(String groupId);
}
