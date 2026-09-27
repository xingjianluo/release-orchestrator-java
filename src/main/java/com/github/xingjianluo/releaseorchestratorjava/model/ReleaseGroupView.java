package com.github.xingjianluo.releaseorchestratorjava.model;

import java.util.List;

/**
 * 发布组只读视图：组记录 + 实时推导的组状态 + 每个成员自己的发布快照。
 * 成员之间完全独立，任一成员的失败/回滚只反映在它自己的条目上。
 */
public record ReleaseGroupView(
        String groupId,
        String groupKey,
        GroupStatus status,
        List<Release> releases) {
}
