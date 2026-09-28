package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.Application;
import com.github.xingjianluo.releaseorchestratorjava.model.GroupOperationResult;
import com.github.xingjianluo.releaseorchestratorjava.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.MemberOutcome;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupView;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseGroupOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.state.ReleaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 进程中断重启后：组记录与组内每个成员的状态、历史各自一致，恢复只影响被中断的成员。 */
class ReleaseGroupRecoveryTest extends AbstractOrchestratorTest {

    private static final String COMMIT = "3333333789abcdef0123456789abcdef01234567";

    @Autowired
    private ReleaseGroupOrchestrator groups;

    @Autowired
    private ReleaseRepository repository;

    private ReleaseRequest member(String service, String content) {
        return new ReleaseRequest(service, COMMIT, content, null, null);
    }

    private Release memberOf(ReleaseGroupView view, String service) {
        return view.releases().stream()
                .filter(r -> r.getServiceName().equals(service)).findFirst().orElseThrow();
    }

    private ConfigurableApplicationContext restartContext() {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .properties("spring.task.scheduling.enabled=false")
                .run(
                        "--spring.main.web-application-type=none",
                        "--release.orchestrator.data-dir=" + dataDir().toAbsolutePath(),
                        "--release.orchestrator.recover-on-startup=false");
    }

    @Test
    void interruptedMemberIsReconciledAloneAndGroupContinuesAfterRestart() {
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-crash", List.of(
                member("svc-r1", "crash-one"),
                member("svc-r2", "crash-two"),
                member("svc-r3", "crash-three"))));
        groups.advanceGroup(group.groupId()); // BUILD
        groups.advanceGroup(group.groupId()); // STAGING

        // 模拟进程在 svc-r2 的 CANARY 执行中被 kill：RUNNING 已落盘，结果永远不写出。
        Release crashed = memberOf(groups.getGroup(group.groupId()), "svc-r2");
        crashed.setStatus(ReleaseStatus.IN_PROGRESS);
        crashed.getStages().get(StageName.CANARY).setStatus(StageStatus.RUNNING);
        crashed.getStages().get(StageName.CANARY).setStartedAt(Instant.now());
        crashed.setUpdatedAt(Instant.now());
        repository.save(crashed);
        testLog.info("input simulated crash: group={} member=svc-r2 CANARY RUNNING persisted",
                group.groupId());

        try (ConfigurableApplicationContext restarted = restartContext()) {
            // 重启时间基准：重启后不允许为被调和成员追加任何 STAGE_STARTED。
            Instant restartedAt = Instant.now();
            ReleaseOrchestrator recoveredReleases = restarted.getBean(ReleaseOrchestrator.class);
            ReleaseGroupOrchestrator recoveredGroups = restarted.getBean(ReleaseGroupOrchestrator.class);

            // 恢复扫描：只有 svc-r2 被调和，组内其余成员原样保留。
            List<ReleaseOrchestrator.RecoveryResult> results = recoveredReleases.recoverInterrupted();
            assertEquals(1, results.size());
            assertEquals(crashed.getReleaseId(), results.get(0).releaseId());
            assertEquals(StageName.CANARY, results.get(0).stage());

            ReleaseGroupView view = recoveredGroups.getGroup(group.groupId());
            Release r2 = memberOf(view, "svc-r2");
            assertEquals(ReleaseStatus.FAILED, r2.getStatus());
            assertEquals("RECOVERED_AFTER_INTERRUPT", r2.getFailureCode());
            assertEquals(StageName.CANARY, r2.getFailureStage());
            // 同伴未被波及：仍停在 STAGING 通过、CANARY 待推进。
            for (String peer : List.of("svc-r1", "svc-r3")) {
                Release p = memberOf(view, peer);
                assertEquals(ReleaseStatus.IN_PROGRESS, p.getStatus(), peer + " untouched");
                assertEquals(StageStatus.PASSED, p.getStages().get(StageName.STAGING).getStatus());
                assertEquals(StageStatus.PENDING, p.getStages().get(StageName.CANARY).getStatus());
            }
            // 已有成员处于失败终态时，组状态即 FAILED，与逐成员结论保持一致：
            // 不会因为其他成员仍 IN_PROGRESS 就把失败掩盖掉。
            assertEquals(GroupStatus.FAILED, view.status());

            // 重启后第一轮推进：失败成员立即记 SKIPPED 并带回恢复时调和出的失败原因，
            // 调用方无需再查一次组详情；健康成员各自推进 CANARY。
            GroupOperationResult firstSweep = recoveredGroups.advanceGroup(group.groupId());
            MemberOutcome r2First = firstSweep.outcomes().stream()
                    .filter(o -> o.serviceName().equals("svc-r2")).findFirst().orElseThrow();
            assertEquals("SKIPPED", r2First.action(),
                    "recovered terminal-failed member is not re-run on first post-restart sweep");
            assertEquals(false, r2First.ok());
            assertEquals("RELEASE_FAILED", r2First.code());
            assertTrue(r2First.message().contains("RECOVERED_AFTER_INTERRUPT"),
                    "first post-restart sweep retains recovered failure reason: " + r2First.message());
            assertTrue(r2First.message().contains("CANARY"),
                    "skip reason names the recovered failing stage: " + r2First.message());
            for (String peer : List.of("svc-r1", "svc-r3")) {
                MemberOutcome po = firstSweep.outcomes().stream()
                        .filter(o -> o.serviceName().equals(peer)).findFirst().orElseThrow();
                assertTrue(po.ok(), peer + " keeps advancing after restart: " + po);
            }

            // 后续两轮：同伴走完全程，被调和成员保持失败终态不再动作。
            GroupOperationResult last = firstSweep;
            for (int i = 0; i < 2; i++) {
                last = recoveredGroups.advanceGroup(group.groupId());
            }
            ReleaseGroupView done = recoveredGroups.getGroup(group.groupId());
            assertEquals(ReleaseStatus.COMPLETED, memberOf(done, "svc-r1").getStatus());
            assertEquals(ReleaseStatus.COMPLETED, memberOf(done, "svc-r3").getStatus());
            Release r2Final = memberOf(done, "svc-r2");
            assertEquals(ReleaseStatus.FAILED, r2Final.getStatus());
            assertEquals("RECOVERED_AFTER_INTERRUPT", r2Final.getFailureCode(),
                    "failure reason survives restart and later sweeps");
            assertEquals(StageName.CANARY, r2Final.getFailureStage());
            assertEquals(GroupStatus.FAILED, done.status());
            MemberOutcome r2Outcome = last.outcomes().stream()
                    .filter(o -> o.serviceName().equals("svc-r2")).findFirst().orElseThrow();
            assertEquals("SKIPPED", r2Outcome.action());
            assertEquals("RELEASE_FAILED", r2Outcome.code());
            assertTrue(r2Outcome.message().contains("RECOVERED_AFTER_INTERRUPT"),
                    "later sweeps keep the recovered failure reason: " + r2Outcome.message());
            // 历史不丢失：失败成员的事件轨迹完整（提交、各阶段、失败）。
            var journal = restarted.getBean(com.github.xingjianluo.releaseorchestratorjava
                    .state.EventJournal.class);
            // 重启后失败成员从未重新执行任何阶段：没有重复部署。
            long r2RestartedStageStarts = journal.readAll().stream()
                    .filter(e -> e.releaseId().equals(crashed.getReleaseId()))
                    .filter(e -> "STAGE_STARTED".equals(e.type()) && !e.at().isBefore(restartedAt))
                    .count();
            assertEquals(0L, r2RestartedStageStarts,
                    "recovered terminal-failed member is never re-executed after restart");
            List<String> r2Events = journal.readAll().stream()
                    .filter(e -> e.releaseId().equals(crashed.getReleaseId()))
                    .map(e -> e.type()).toList();
            assertTrue(r2Events.contains("RELEASE_SUBMITTED"));
            assertTrue(r2Events.contains("STAGE_STARTED"));
            assertTrue(r2Events.contains("RELEASE_FAILED"));
            testLog.info("final state after restart: svc-r1/svc-r3 COMPLETED, svc-r2 FAILED({}), events={}",
                    r2Final.getFailureCode(), r2Events);
        }
    }
}
