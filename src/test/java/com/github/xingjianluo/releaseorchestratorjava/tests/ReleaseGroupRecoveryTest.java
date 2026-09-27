package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.Application;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.release.ReleaseGroupOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布组在进程中断重启后的一致性：成员 RUNNING 调和、缺失成员补齐、组状态刷新、幂等。 */
class ReleaseGroupRecoveryTest extends AbstractOrchestratorTest {

    @Autowired
    private ReleaseGroupOrchestrator groups;

    @Autowired
    private com.github.xingjianluo.releaseorchestratorjava.state.ReleaseRepository releaseRepository;

    @Autowired
    private ObjectMapper mapper;

    private ConfigurableApplicationContext restartContext() {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .properties("spring.task.scheduling.enabled=false")
                .run(
                        "--spring.main.web-application-type=none",
                        "--release.orchestrator.data-dir=" + dataDir().toAbsolutePath(),
                        "--release.orchestrator.recover-on-startup=false");
    }

    private Release completeStandalone(String service, String commit, String content) {
        Release r = orchestrator.submit(new com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest(
                service, commit, content, null, null));
        for (int i = 0; i < StageName.ordered().size(); i++) {
            r = orchestrator.advance(r.getReleaseId());
        }
        return r;
    }

    @Test
    void groupMemberRunningStageAfterRestartReconciledWithoutAffectingOtherMembers() {
        // 先为 billing 准备一个稳定版本，供调和为 FAILED 后回滚
        Release stable = completeStandalone("billing-api",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "stable");

        ReleaseGroup group = groups.submit(new ReleaseGroupRequest("rec-1", List.of(
                new GroupMemberRequest("billing-api",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "in-flight", null),
                new GroupMemberRequest("edge-api",
                        "cccccccccccccccccccccccccccccccccccccccc", "healthy", null))));
        groups.advance(group.getGroupId()); // BUILD
        groups.advance(group.getGroupId()); // STAGING

        String billingId = group.getMembers().get(0).getReleaseId();
        String edgeId = group.getMembers().get(1).getReleaseId();

        // 模拟 billing 在 CANARY RUNNING 时被 kill；edge 已健康通过 STAGING
        Release billing = releaseRepository.find(billingId).orElseThrow();
        billing.setStatus(ReleaseStatus.IN_PROGRESS);
        billing.getStages().get(StageName.CANARY).setStatus(StageStatus.RUNNING);
        billing.getStages().get(StageName.CANARY).setStartedAt(Instant.now());
        billing.setUpdatedAt(Instant.now());
        releaseRepository.save(billing);

        try (ConfigurableApplicationContext restarted = restartContext()) {
            var recoveredReleases = restarted.getBean(
                    com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator.class);
            ReleaseGroupOrchestrator recoveredGroups = restarted.getBean(ReleaseGroupOrchestrator.class);

            // 第一层：成员发布 RUNNING -> FAILED(RECOVERED_AFTER_INTERRUPT)
            var releaseResults = recoveredReleases.recoverInterrupted();
            assertEquals(1, releaseResults.size());
            assertEquals(billingId, releaseResults.get(0).releaseId());

            // 第二层：组状态与成员快照刷新为“有失败但其余成员存在” => DEGRADED
            int touched = recoveredGroups.recoverInterrupted();
            assertTrue(touched >= 1);
            ReleaseGroup refreshed = recoveredGroups.get(group.getGroupId());
            assertEquals(GroupStatus.DEGRADED, refreshed.getStatus());

            Release billingAfter = recoveredReleases.get(billingId);
            assertEquals(ReleaseStatus.FAILED, billingAfter.getStatus());
            assertEquals("RECOVERED_AFTER_INTERRUPT", billingAfter.getFailureCode());

            // edge 不受影响，可以继续独立推进到完成
            recoveredReleases.advance(edgeId); // CANARY
            recoveredReleases.advance(edgeId); // PRODUCTION
            assertEquals(ReleaseStatus.COMPLETED, recoveredReleases.get(edgeId).getStatus());

            // billing 按服务回滚到它自己的上一个稳定版
            Release rolledBack = recoveredGroups.rollbackMember(
                    group.getGroupId(), "billing-api");
            assertEquals(ReleaseStatus.ROLLED_BACK, rolledBack.getStatus());
            assertEquals(stable.getVersion(), rolledBack.getRolledBackToVersion());

            // 再恢复一次是幂等空操作（成员已无缺失，组状态已一致）
            int again = recoveredGroups.recoverInterrupted();
            assertEquals(0, again);
        }
    }

    @Test
    void groupFilePresentButMemberReleaseMissingIsRebuiltOnRestart() throws Exception {
        ReleaseGroup group = groups.submit(new ReleaseGroupRequest("rec-2", List.of(
                new GroupMemberRequest("billing-api",
                        "1111111111111111111111111111111111111111", "content-x", null))));
        String memberReleaseId = group.getMembers().get(0).getReleaseId();

        // 模拟“组元数据已落盘、成员 Release 状态文件丢失”的中断现场
        java.nio.file.Path stateFile = dataDir().resolve("releases").resolve(memberReleaseId + ".json");
        java.nio.file.Files.delete(stateFile);
        testLog.info("simulated crash: deleted member release file {}", stateFile);

        try (ConfigurableApplicationContext restarted = restartContext()) {
            ReleaseGroupOrchestrator recoveredGroups = restarted.getBean(ReleaseGroupOrchestrator.class);
            var recoveredReleases = restarted.getBean(
                    com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator.class);

            int touched = recoveredGroups.recoverInterrupted();
            assertTrue(touched >= 1);

            // 成员以相同身份被确定性补齐，历史可继续推进
            Release rebuilt = recoveredReleases.get(memberReleaseId);
            assertNotNull(rebuilt);
            assertEquals(group.getGroupId(), rebuilt.getGroupId());
            assertEquals(ReleaseStatus.PENDING, rebuilt.getStatus());

            for (int i = 0; i < 4; i++) {
                recoveredGroups.advance(group.getGroupId());
            }
            assertEquals(ReleaseStatus.COMPLETED,
                    recoveredReleases.get(memberReleaseId).getStatus());
            assertEquals(GroupStatus.COMPLETED,
                    recoveredGroups.get(group.getGroupId()).getStatus());
        }
    }
}
