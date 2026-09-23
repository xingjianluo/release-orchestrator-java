package com.github.xingjianluo.releaseorchestratorjava.model;

import java.util.List;

/** 发布阶段，顺序即数组顺序，禁止跳过。 */
public enum StageName {
    BUILD,
    STAGING,
    CANARY,
    PRODUCTION;

    private static final List<StageName> ORDER = List.of(values());

    public int order() {
        return ORDER.indexOf(this);
    }

    public static List<StageName> ordered() {
        return ORDER;
    }

    public StageName next() {
        int i = order();
        return i + 1 < ORDER.size() ? ORDER.get(i + 1) : null;
    }
}
