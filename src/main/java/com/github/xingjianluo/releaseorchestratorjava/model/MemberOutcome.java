package com.github.xingjianluo.releaseorchestratorjava.model;

/**
 * 组级批量操作中单个成员的执行结论。
 * 组内故障隔离的载体：某个成员失败只体现为它自己的 ok=false 与错误码，
 * 不影响其他成员继续执行。
 *
 * <p>判定以成员落盘真实状态为准，与组内真实结果严格一致：
 * <ul>
 *   <li>{@code ADVANCE/ok=true}：本轮阶段真正执行并通过；</li>
 *   <li>{@code ADVANCE/ok=false}：本轮阶段执行失败（code 为原始失败码，
 *       message 携带失败阶段与原因），失败在发生当轮即返回；</li>
 *   <li>{@code SKIPPED/ok=false}：本轮未执行新动作——成员已在终态
 *       （{@code RELEASE_FAILED} 等，消息保留原失败原因）或成员锁被并发触发方持有
 *       （{@code MEMBER_BUSY}）；终态失败成员永远跳过、永不重跑。</li>
 * </ul>
 *
 * @param action  本次对该成员执行的动作（ADVANCE / ROLLBACK / SKIPPED）
 * @param ok      动作是否成功（SKIPPED 恒为 false，并携带原因码）
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
