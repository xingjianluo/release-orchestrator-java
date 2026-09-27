package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberResult;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.release.ReleaseGroupOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布组基础路径：多服务同提交/同内容也各自成立、独立身份、独立走完全部阶段。 */
public class ReleaseGroupBasicTest extends AbstractOrchestratorTest {

    @Autowired
    private ReleaseGroupOrchestrator groups;

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String SAME_CONTENT = "identical source across services";

    @Test
    void identicalInputsAcrossServicesStayIndependentAndBothComplete() {
        // 两个服务使用完全相同的 commit 与 sourceContent（单仓多服务常见场景）
        ReleaseGroupRequest request = new ReleaseGroupRequest("ticket-group-1", List.of(
                new GroupMemberRequest("billing-api", COMMIT, SAME_CONTENT, null),
                new GroupMemberRequest("edge-api", COMMIT, SAME_CONTENT, null)));

        ReleaseGroup group = groups.submit(request);
        assertEquals(2, group.getMembers().size());
        assertEquals(GroupStatus.PENDING, group.getStatus());

        Release billing = orchestrator.get(group.getMembers().get(0).getReleaseId());
        Release edge = orchestrator.get(group.getMembers().get(1).getReleaseId());

        // 关键不变量：同 commit + 同内容，但身份/版本对象/状态文件各不相同
        assertNotEquals(billing.getReleaseId(), edge.getReleaseId());
        assertTrue(billing.getReleaseId().contains("billing-api"));
        assertTrue(edge.getReleaseId().contains("edge-api"));
        assertEquals(group.getGroupId(), billing.getGroupId());
        assertEquals(group.getGroupId(), edge.getGroupId());
        // 版本字符串相同（输入相同），但它们是两条独立发布
        assertEquals(billing.getVersion(), edge.getVersion());

        // 逐阶段推进 4 次，每次都应同时推进两个成员
        for (int i = 0; i < 4; i++) {
            List<GroupMemberResult> results = groups.advance(group.getGroupId());
            assertEquals(2, results.size());
            for (GroupMemberResult r : results) {
                assertTrue(r.advanced(), "member " + r.serviceName() + " should advance at round " + i);
                assertEquals(null, r.errorCode());
            }
        }

        ReleaseGroup done = groups.get(group.getGroupId());
        assertEquals(GroupStatus.COMPLETED, done.getStatus());
        for (var member : done.getMembers()) {
            Release r = orchestrator.get(member.getReleaseId());
            assertEquals(ReleaseStatus.COMPLETED, r.getStatus());
            assertEquals(com.github.xingjianluo.releaseorchestratorjava.model.StageStatus.PASSED,
                    r.getStages().get(StageName.PRODUCTION).getStatus());
        }
        // 两条独立部署指针都已写入
        assertTrue(java.nio.file.Files.exists(
                dataDir().resolve("deployments/billing-api/current.json")));
        assertTrue(java.nio.file.Files.exists(
                dataDir().resolve("deployments/edge-api/current.json")));
    }

    @Test
    void groupStatusIsInProgressAfterFirstRound() {
        ReleaseGroup group = groups.submit(new ReleaseGroupRequest("status-1", List.of(
                new GroupMemberRequest("billing-api", COMMIT, SAME_CONTENT, null),
                new GroupMemberRequest("edge-api", COMMIT, SAME_CONTENT, null))));
        groups.advance(group.getGroupId()); // 只过 BUILD，无人 COMPLETED
        assertEquals(GroupStatus.IN_PROGRESS, groups.get(group.getGroupId()).getStatus());
    }

    @Test
    void duplicateGroupSubmissionIsRejectedButGroupStillReusable() {
        ReleaseGroupRequest request = new ReleaseGroupRequest("ticket-dup", List.of(
                new GroupMemberRequest("billing-api", COMMIT, SAME_CONTENT, null),
                new GroupMemberRequest("edge-api", COMMIT, SAME_CONTENT, null)));
        ReleaseGroup first = groups.submit(request);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException.class,
                () -> groups.submit(request));
        assertEquals("DUPLICATE_SUBMISSION", ex.getCode());

        // 既有组可继续查询/推进，成员仍是原来那两条发布，没有被重新创建
        ReleaseGroup again = groups.get(first.getGroupId());
        assertEquals(first.getMembers().get(0).getReleaseId(),
                again.getMembers().get(0).getReleaseId());
    }

    @Test
    void sameServiceInputAcrossGroupAndStandaloneIsOneDeployment() {
        // 组里先发 billing-api
        ReleaseGroup group = groups.submit(new ReleaseGroupRequest("ticket-cross-1", List.of(
                new GroupMemberRequest("billing-api", COMMIT, SAME_CONTENT, null))));
        String groupReleaseId = group.getMembers().get(0).getReleaseId();

        // 再用完全相同输入走原单服务接口：必须识别为同一条，而不是第二条发布
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException.class,
                () -> orchestrator.submit(new com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest(
                        "billing-api", COMMIT, SAME_CONTENT, "standalone-1", null)));
        assertEquals("DUPLICATE_SUBMISSION", ex.getCode());
        assertTrue(ex.getMessage().contains(groupReleaseId));
    }
}
