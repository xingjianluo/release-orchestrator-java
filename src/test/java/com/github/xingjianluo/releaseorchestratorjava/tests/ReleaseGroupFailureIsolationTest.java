package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberResult;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.release.ReleaseGroupOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 组内故障隔离 + 按服务回滚：一个服务失败不牵连其他服务，回滚各回各的上一个稳定版。 */
public class ReleaseGroupFailureIsolationTest extends AbstractOrchestratorTest {

    @Autowired
    private ReleaseGroupOrchestrator groups;

    private static final String COMMIT = "abcdef0123456789abcdef0123456789abcdef01";

    @Test
    void oneMemberFailsOthersContinueToCompletion() {
        // 3 个服务同提交；billing 在 CANARY 必然失败，另外两个健康
        ReleaseGroup group = groups.submit(new ReleaseGroupRequest("iso-1", List.of(
                new GroupMemberRequest("billing-api", COMMIT, "content-billing", "fail:CANARY"),
                new GroupMemberRequest("edge-api", COMMIT, "content-edge", null),
                new GroupMemberRequest("auth-api", COMMIT, "content-auth", null))));

        // 4 轮推进：billing 会在第 3 轮失败（该轮结果携带 CANARY 失败码）；之后它被跳过，其余继续
        GroupMemberResult billingFailureRound = null;
        List<GroupMemberResult> last = null;
        for (int i = 0; i < 4; i++) {
            last = groups.advance(group.getGroupId());
            final int round = i;
            GroupMemberResult billingAtRound = last.stream()
                    .filter(r -> r.serviceName().equals("billing-api")).findFirst().orElseThrow();
            if (round == 2) {
                billingFailureRound = billingAtRound;
            }
        }

        // billing 在第 3 轮实际尝试了 CANARY 但失败（本轮算一次尝试），携带 CANARY 失败码
        assertEquals(true, billingFailureRound.advanced());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", billingFailureRound.errorCode());
        assertEquals(ReleaseStatus.FAILED, billingFailureRound.status());

        GroupMemberResult billingResult = last.stream()
                .filter(r -> r.serviceName().equals("billing-api")).findFirst().orElseThrow();
        // 终态轮次：失败成员不再尝试、被跳过，结论仍稳定为 FAILED（原因以其 Release 终态为准）
        assertEquals(false, billingResult.advanced());
        assertEquals(ReleaseStatus.FAILED, billingResult.status());

        Release billing = orchestrator.get(billingResult.releaseId());
        assertEquals(ReleaseStatus.FAILED, billing.getStatus());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", billing.getFailureCode());
        assertNotNull(billing.getFailureReason());

        Release edge = orchestrator.get(last.stream()
                .filter(r -> r.serviceName().equals("edge-api")).findFirst().orElseThrow().releaseId());
        Release auth = orchestrator.get(last.stream()
                .filter(r -> r.serviceName().equals("auth-api")).findFirst().orElseThrow().releaseId());
        assertEquals(ReleaseStatus.COMPLETED, edge.getStatus());
        assertEquals(ReleaseStatus.COMPLETED, auth.getStatus());

        // 组终态：有失败也有完成 => COMPLETED_WITH_FAILURES
        ReleaseGroup terminal = groups.get(group.getGroupId());
        assertEquals(GroupStatus.COMPLETED_WITH_FAILURES, terminal.getStatus());

        // 对失败成员再推进组，billing 依旧是带原因的失败终态（可解释、不被顶掉）
        List<GroupMemberResult> extra = groups.advance(group.getGroupId());
        GroupMemberResult billingSkipped = extra.stream()
                .filter(r -> r.serviceName().equals("billing-api")).findFirst().orElseThrow();
        assertEquals(false, billingSkipped.advanced());
        assertEquals(ReleaseStatus.FAILED, billingSkipped.status());
    }

    @Test
    void groupRollbackTouchesOnlyFailedMemberAndUsesEachOwnStableVersion() {
        // 先让三个服务都发布一个健康的稳定版本（各自的稳定登记册是独立的）
        ReleaseGroup stable = groups.submit(new ReleaseGroupRequest("rb-stable", List.of(
                new GroupMemberRequest("billing-api", "1111111111111111111111111111111111111111", "v1", null),
                new GroupMemberRequest("edge-api", "2222222222222222222222222222222222222222", "v1", null))));
        for (int i = 0; i < 4; i++) {
            groups.advance(stable.getGroupId());
        }

        // 第二组：billing 新版本失败，edge 新版本成功
        ReleaseGroup next = groups.submit(new ReleaseGroupRequest("rb-next", List.of(
                new GroupMemberRequest("billing-api", COMMIT, "v2-billing", "fail:STAGING"),
                new GroupMemberRequest("edge-api", "3333333333333333333333333333333333333333", "v2-edge", null))));
        for (int i = 0; i < 4; i++) {
            groups.advance(next.getGroupId());
        }

        String billingId = next.getMembers().stream()
                .filter(m -> m.getServiceName().equals("billing-api")).findFirst().orElseThrow().getReleaseId();
        String edgeId = next.getMembers().stream()
                .filter(m -> m.getServiceName().equals("edge-api")).findFirst().orElseThrow().getReleaseId();
        assertEquals(ReleaseStatus.FAILED, orchestrator.get(billingId).getStatus());
        assertEquals(ReleaseStatus.COMPLETED, orchestrator.get(edgeId).getStatus());

        // 组级回滚：只回滚 FAILED 的 billing；COMPLETED 的 edge 不受牵连
        List<GroupMemberResult> rb = groups.rollback(next.getGroupId());
        GroupMemberResult billingRb = rb.stream().filter(r -> r.serviceName().equals("billing-api")).findFirst().orElseThrow();
        GroupMemberResult edgeRb = rb.stream().filter(r -> r.serviceName().equals("edge-api")).findFirst().orElseThrow();
        assertEquals(true, billingRb.advanced());
        assertEquals(ReleaseStatus.ROLLED_BACK, billingRb.status());
        assertNull(billingRb.errorCode());
        // edge 被跳过，未回滚
        assertEquals(false, edgeRb.advanced());
        assertEquals(ReleaseStatus.COMPLETED, edgeRb.status());

        Release billingAfter = orchestrator.get(billingId);
        assertEquals(ReleaseStatus.ROLLED_BACK, billingAfter.getStatus());
        assertNotNull(billingAfter.getRolledBackFromVersion());
        assertNotNull(billingAfter.getRolledBackToVersion());
        // edge 仍然停在新版本 COMPLETED，没被回滚
        assertEquals(ReleaseStatus.COMPLETED, orchestrator.get(edgeId).getStatus());

        // 组级回滚幂等：再来一次不重复变更
        List<GroupMemberResult> rb2 = groups.rollback(next.getGroupId());
        GroupMemberResult billingRb2 = rb2.stream().filter(r -> r.serviceName().equals("billing-api")).findFirst().orElseThrow();
        assertEquals(false, billingRb2.advanced());
        assertEquals(ReleaseStatus.ROLLED_BACK, billingRb2.status());
    }

    @Test
    void rollbackMemberRollsBackJustThatService() {
        ReleaseGroup stable = groups.submit(new ReleaseGroupRequest("rm-stable", List.of(
                new GroupMemberRequest("billing-api", "1111111111111111111111111111111111111111", "v1", null),
                new GroupMemberRequest("edge-api", "2222222222222222222222222222222222222222", "v1", null))));
        for (int i = 0; i < 4; i++) {
            groups.advance(stable.getGroupId());
        }
        ReleaseGroup next = groups.submit(new ReleaseGroupRequest("rm-next", List.of(
                new GroupMemberRequest("billing-api", COMMIT, "v2", "fail:CANARY"),
                new GroupMemberRequest("edge-api", "4444444444444444444444444444444444444444", "v2", "fail:CANARY"))));
        for (int i = 0; i < 4; i++) {
            groups.advance(next.getGroupId());
        }

        // 只回滚 billing，edge 必须保持 FAILED 不动
        Release billing = groups.rollbackMember(next.getGroupId(), "billing-api");
        assertEquals(ReleaseStatus.ROLLED_BACK, billing.getStatus());
        String edgeId = next.getMembers().stream()
                .filter(m -> m.getServiceName().equals("edge-api")).findFirst().orElseThrow().getReleaseId();
        assertEquals(ReleaseStatus.FAILED, orchestrator.get(edgeId).getStatus());

        // 回滚一个不属于该组的服务 => 404 类错误
        ReleaseException ex = assertThrows(ReleaseException.class,
                () -> groups.rollbackMember(next.getGroupId(), "ghost-api"));
        assertEquals("GROUP_MEMBER_NOT_FOUND", ex.getCode());
    }
}
