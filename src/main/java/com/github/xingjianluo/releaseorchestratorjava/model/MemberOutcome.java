package com.github.xingjianluo.releaseorchestratorjava.model;

/**
 * 组级批量操作中单个成员的执行结论。
 * 组内故障隔离的载体：某个成员失败只体现为它自己的 ok=false 与错误码，
 * 不影响其他成员继续执行。
 *
 * @param action  本次对该成员执行的动作（ADVANCE / ROLLBACK / SKIPPED）
 * @param ok      动作是否成功（SKIPPED 时 ok=false 并携带原因码）
 * @param code    稳定错误码（成功时为 null）
 */
public record MemberOutcome(
        String serviceName,
        String releaseId,
        String action,
        boolean ok,
        String code,
        String message) {

    public static MemberOutcome success(String serviceName, String releaseId, String action) {
        return new MemberOutcome(serviceName, releaseId, action, true, null, null);
    }

    public static MemberOutcome failure(String serviceName, String releaseId, String action,
                                        String code, String message) {
        return new MemberOutcome(serviceName, releaseId, action, false, code, message);
    }
}
