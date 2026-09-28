package com.github.xingjianluo.releaseorchestratorjava.tests;

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
import com.github.xingjianluo.releaseorchestratorjava.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 组级推进逐成员结论语义：
 * 失败发生的那一轮就必须在返回值里明确失败；后续轮次只跳过并保留原始失败原因；
 * 健康成员继续推进；逐成员结论与组状态始终一致。
 */
class ReleaseGroupMemberOutcomeTest extends AbstractOrchestratorTest {

    private static final String COMMIT = "5555555789abcdef0123456789abcdef01234567";

    @Autowired
    private ReleaseGroupOrchestrator groups;

    @Autowired
    private ReleaseRepository repository;

    private ReleaseRequest member(String service, String content, String fault) {
        return new ReleaseRequest(service, COMMIT, content, null, fault);
    }

    private MemberOutcome outcomeOf(GroupOperationResult result, String service) {
        return result.outcomes().stream()
                .filter(o -> o.serviceName().equals(service)).findFirst().orElseThrow();
    }

    @Test
    void failingRoundReportsFailureImmediatelyAndPeersKeepAdvancing() {
        // svc-bad 在 CANARY 必然失败；另两个服务全程健康。
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-outcome", List.of(
                member("svc-good-a", "good-a", null),
                member("svc-good-b", "good-b", null),
                member("svc-bad", "bad-v2", "fail:CANARY"))));

        groups.advanceGroup(group.groupId()); // BUILD：三个成员都成功
        groups.advanceGroup(group.groupId()); // STAGING：三个成员都成功

        // 第三轮：svc-bad 在 CANARY 执行失败——这一轮的返回值必须直接给出失败结论。
        GroupOperationResult failingSweep = groups.advanceGroup(group.groupId());
        MemberOutcome bad = outcomeOf(failingSweep, "svc-bad");
        assertFalse(bad.ok(), "failing member must not be reported as success: " + bad);
        assertEquals("ADVANCE", bad.action(), "failure round is an executed ADVANCE, not a skip");
        assertEquals("CANARY_HEALTH_CHECK_FAILED", bad.code(),
                "outcome carries the recognizable failure code");
        assertNotNull(bad.message(), "outcome carries a human-readable failure explanation");
        assertTrue(bad.message().contains("CANARY"), "message identifies the failed stage: " + bad.message());

        // 同一轮里其他服务照常推进到 CANARY，不被失败成员牵连。
        for (String good : List.of("svc-good-a", "svc-good-b")) {
            MemberOutcome o = outcomeOf(failingSweep, good);
            assertTrue(o.ok(), "healthy peer still advances in the failing round: " + o);
            assertEquals("ADVANCE", o.action());
        }
        // 组状态与逐成员结论一致：此刻同伴仍在推进，组为 IN_PROGRESS；
        // 失败已经能在当轮返回的逐成员结论里直接看到，无需再查组详情。
        assertEquals(GroupStatus.IN_PROGRESS, failingSweep.group().status());

        // 失败结论与持久化的成员真实结果完全一致（不是“返回成功、实际失败”）。
        var badRelease = failingSweep.group().releases().stream()
                .filter(r -> r.getServiceName().equals("svc-bad")).findFirst().orElseThrow();
        assertEquals(ReleaseStatus.FAILED, badRelease.getStatus());
        assertEquals(StageName.CANARY, badRelease.getFailureStage());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", badRelease.getFailureCode());

        // 再一轮：同伴全部完成，失败成员保持 FAILED，组结论收敛为 FAILED。
        GroupOperationResult finalSweep = groups.advanceGroup(group.groupId());
        assertEquals(GroupStatus.FAILED, finalSweep.group().status());
        for (String good : List.of("svc-good-a", "svc-good-b")) {
            assertTrue(outcomeOf(finalSweep, good).ok());
        }
        MemberOutcome badSkipped = outcomeOf(finalSweep, "svc-bad");
        assertEquals("SKIPPED", badSkipped.action());
        assertEquals("RELEASE_FAILED", badSkipped.code());
        testLog.info("failing sweep outcomes={} then group={}", failingSweep.outcomes(),
                finalSweep.group().status());
    }

    @Test
    void laterSweepsSkipTerminalFailureAndKeepOriginalReasonWhilePeersComplete() {
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-skip", List.of(
                member("svc-well-1", "well-1", null),
                member("svc-well-2", "well-2", "fail:STAGING"))));

        groups.advanceGroup(group.groupId()); // BUILD：都成功
        GroupOperationResult failingSweep = groups.advanceGroup(group.groupId()); // STAGING：svc-well-2 失败
        MemberOutcome failedRound = outcomeOf(failingSweep, "svc-well-2");
        assertFalse(failedRound.ok());
        assertEquals("STAGING_HEALTH_CHECK_FAILED", failedRound.code());

        // 失败进入终态后再推进两轮：失败成员只能被跳过，且每轮都带原始失败原因。
        GroupOperationResult later1 = groups.advanceGroup(group.groupId());
        GroupOperationResult later2 = groups.advanceGroup(group.groupId());
        for (GroupOperationResult later : List.of(later1, later2)) {
            MemberOutcome skipped = outcomeOf(later, "svc-well-2");
            assertEquals("SKIPPED", skipped.action(),
                    "terminal failure is never re-run: " + skipped);
            assertFalse(skipped.ok());
            assertEquals("RELEASE_FAILED", skipped.code());
            assertNotNull(skipped.message());
            assertTrue(skipped.message().contains("STAGING"),
                    "skip keeps the original failed stage: " + skipped.message());
            assertTrue(skipped.message().contains("STAGING_HEALTH_CHECK_FAILED"),
                    "skip keeps the original failure code: " + skipped.message());
            // 组结论由成员状态推导：同伴还在推进时 IN_PROGRESS，同伴完成后收敛 FAILED。
            assertTrue(later.group().status() == GroupStatus.IN_PROGRESS
                            || later.group().status() == GroupStatus.FAILED,
                    "group status derived purely from member outcomes");
        }
        assertEquals(GroupStatus.FAILED, later2.group().status(),
                "once peers finish, group conclusion matches the failed member");

        // 健康同伴不受影响，最终全部 COMPLETED。
        ReleaseGroupView done = groups.getGroup(group.groupId());
        assertEquals(ReleaseStatus.COMPLETED,
                done.releases().stream().filter(r -> r.getServiceName().equals("svc-well-1"))
                        .findFirst().orElseThrow().getStatus());
        var badFinal = done.releases().stream()
                .filter(r -> r.getServiceName().equals("svc-well-2")).findFirst().orElseThrow();
        assertEquals(ReleaseStatus.FAILED, badFinal.getStatus());
        assertEquals(StageName.STAGING, badFinal.getFailureStage());
        assertEquals("STAGING_HEALTH_CHECK_FAILED", badFinal.getFailureCode());
        assertEquals(GroupStatus.FAILED, done.status());

        // 被跳过的失败成员没有重新执行任何阶段：STAGE_STARTED 在每个阶段只出现一次。
        long badStarts = journal.readAll().stream()
                .filter(e -> e.releaseId().equals(badFinal.getReleaseId())
                        && "STAGE_STARTED".equals(e.type())).count();
        assertEquals(2, badStarts, "failed member is not re-deployed after terminal failure");
        testLog.info("final state: terminal failure skipped with reason preserved, peer completed");
    }

    @Test
    void concurrentSweepsStabilizeFailureOutcomeAndExactlyOnceStages() throws Exception {
        // 组内一个成员在 CANARY 失败，其余健康：多线程并发扫组直到组进入终态。
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-conc-outcome", List.of(
                member("svc-c-1", "c-1", null),
                member("svc-c-2", "c-2", null),
                member("svc-c-3", "c-3", null),
                member("svc-c-bad", "c-bad", "fail:CANARY"))));
        String badId = group.releases().stream()
                .filter(r -> r.getServiceName().equals("svc-c-bad")).findFirst().orElseThrow()
                .getReleaseId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> badOutcomeSignatures = ConcurrentHashMap.newKeySet();
        AtomicInteger badFailureReports = new AtomicInteger();
        AtomicInteger badSkipReports = new AtomicInteger();
        AtomicInteger badSuccessReports = new AtomicInteger();
        AtomicInteger sweeps = new AtomicInteger();
        AtomicInteger terminalGroupStatusSeen = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int iter = 0; iter < 100; iter++) {
                        GroupOperationResult result = groups.advanceGroup(group.groupId());
                        sweeps.incrementAndGet();
                        MemberOutcome bad = outcomeOf(result, "svc-c-bad");
                        Release badState = result.group().releases().stream()
                                .filter(r -> r.getReleaseId().equals(badId)).findFirst().orElseThrow();

                        // 核心不变量：逐成员结论必须与同轮返回视图里的成员真实状态一致。
                        if (badState.getStatus() == ReleaseStatus.FAILED) {
                            assertFalse(bad.ok(),
                                    "failed member must never be reported success: " + bad);
                            if ("ADVANCE".equals(bad.action())) {
                                // 失败发生的这一轮：失败码即阶段失败码。
                                assertEquals(badState.getFailureCode(), bad.code());
                                badFailureReports.incrementAndGet();
                            } else {
                                // 后续轮次：只能跳过，码为 RELEASE_FAILED，原因保留。
                                assertEquals("SKIPPED", bad.action());
                                assertEquals("RELEASE_FAILED", bad.code());
                                assertTrue(bad.message().contains("CANARY"));
                                assertTrue(bad.message().contains("CANARY_HEALTH_CHECK_FAILED"));
                                badSkipReports.incrementAndGet();
                            }
                            badOutcomeSignatures.add(bad.code() + "|" + bad.action());
                        } else {
                            // 尚未失败的轮次：要么正常推进成功，要么与别的 sweeper 撞锁跳过。
                            assertTrue(bad.ok() || ("SKIPPED".equals(bad.action())
                                            && "RELEASE_BUSY".equals(bad.code())),
                                    "pre-failure outcome must be success or busy-skip: " + bad);
                            if (bad.ok()) {
                                badSuccessReports.incrementAndGet();
                            }
                        }
                        if (result.group().status() == GroupStatus.FAILED) {
                            terminalGroupStatusSeen.incrementAndGet();
                        }
                        if (result.group().status() == GroupStatus.COMPLETED) {
                            fail("group with a failed member can never be reported COMPLETED");
                        }
                        if (result.group().status() == GroupStatus.FAILED
                                || result.group().status() == GroupStatus.COMPLETED) {
                            return;
                        }
                    }
                    throw new IllegalStateException("group did not reach terminal state in budget");
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "all sweeper threads finish");

        // 失败成员在失败当轮至少被报一次失败、终态后至少被报一次跳过，从无矛盾结论。
        assertTrue(badFailureReports.get() >= 1, "at least one sweep reports the actual failure");
        assertTrue(badSkipReports.get() >= 1, "later sweeps report the terminal failure as skipped");
        assertTrue(badOutcomeSignatures.contains("CANARY_HEALTH_CHECK_FAILED|ADVANCE"));
        assertTrue(badOutcomeSignatures.contains("RELEASE_FAILED|SKIPPED"));
        // 所有观察到终态的轮次都一致认为组 FAILED，从未串台成 COMPLETED。
        assertTrue(terminalGroupStatusSeen.get() >= 1);

        ReleaseGroupView done = groups.getGroup(group.groupId());
        assertEquals(GroupStatus.FAILED, done.status());
        var badFinal = done.releases().stream()
                .filter(r -> r.getReleaseId().equals(badId)).findFirst().orElseThrow();
        assertEquals(ReleaseStatus.FAILED, badFinal.getStatus());
        assertEquals(StageName.CANARY, badFinal.getFailureStage());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", badFinal.getFailureCode());

        // 每个成员每个阶段的 STAGE_STARTED 恰好一次：并发不重跑、不跳阶段、不重复部署。
        for (var r : done.releases()) {
            long starts = journal.readAll().stream()
                    .filter(e -> e.releaseId().equals(r.getReleaseId())
                            && "STAGE_STARTED".equals(e.type())).count();
            long expected = r.getStatus() == ReleaseStatus.COMPLETED ? 4 : 3;
            assertEquals(expected, starts,
                    r.getServiceName() + " stage starts must stay exactly-once under concurrency");
        }
        long allStarts = journal.readAll().stream()
                .filter(e -> "STAGE_STARTED".equals(e.type())).count();
        assertEquals(4 * 4L - 1, allStarts, "4 members x 4 stages, failed member stops at CANARY");
        testLog.info("concurrent sweeps={} failureReports={} skipReports={} successReports={}",
                sweeps.get(), badFailureReports.get(), badSkipReports.get(), badSuccessReports.get());
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
    void outcomesStayConsistentAfterRestartRecoveryFromCrashMidStage() {
        // 崩溃场景：svc-k2 的 CANARY RUNNING 已落盘、结果未写出；svc-k1/svc-k3 停在 STAGING 通过。
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-restart", List.of(
                member("svc-k1", "k-1", null),
                member("svc-k2", "k-2", null),
                member("svc-k3", "k-3", null))));
        groups.advanceGroup(group.groupId()); // BUILD
        groups.advanceGroup(group.groupId()); // STAGING

        Release crashed = group.releases().stream()
                .filter(r -> r.getServiceName().equals("svc-k2")).findFirst().orElseThrow();
        crashed.setStatus(ReleaseStatus.IN_PROGRESS);
        crashed.getStages().get(StageName.CANARY).setStatus(StageStatus.RUNNING);
        crashed.getStages().get(StageName.CANARY).setStartedAt(Instant.now());
        crashed.setUpdatedAt(Instant.now());
        repository.save(crashed);

        try (ConfigurableApplicationContext restarted = restartContext()) {
            ReleaseOrchestrator recoveredReleases = restarted.getBean(ReleaseOrchestrator.class);
            ReleaseGroupOrchestrator recoveredGroups = restarted.getBean(ReleaseGroupOrchestrator.class);

            // 启动恢复：只有被中断的成员调和为 FAILED(RECOVERED_AFTER_INTERRUPT)。
            var recovered = recoveredReleases.recoverInterrupted();
            assertEquals(1, recovered.size());

            // 恢复后每一轮组推进：被调和成员只记 SKIPPED 并保留恢复原因；同伴继续推进。
            GroupOperationResult last = null;
            for (int round = 0; round < 3; round++) {
                last = recoveredGroups.advanceGroup(group.groupId());
                MemberOutcome k2 = outcomeOf(last, "svc-k2");
                assertFalse(k2.ok());
                assertEquals("SKIPPED", k2.action(),
                        "recovered failure is never re-run after restart: " + k2);
                assertEquals("RELEASE_FAILED", k2.code());
                assertTrue(k2.message().contains("CANARY"),
                        "skip keeps recovered failed stage: " + k2.message());
                assertTrue(k2.message().contains("RECOVERED_AFTER_INTERRUPT"),
                        "skip keeps the recovery failure code: " + k2.message());
            }

            ReleaseGroupView done = recoveredGroups.getGroup(group.groupId());
            assertEquals(GroupStatus.FAILED, done.status());
            Release k2Final = done.releases().stream()
                    .filter(r -> r.getServiceName().equals("svc-k2")).findFirst().orElseThrow();
            assertEquals(ReleaseStatus.FAILED, k2Final.getStatus());
            assertEquals(StageName.CANARY, k2Final.getFailureStage());
            assertEquals("RECOVERED_AFTER_INTERRUPT", k2Final.getFailureCode());
            for (String peer : List.of("svc-k1", "svc-k3")) {
                assertEquals(ReleaseStatus.COMPLETED,
                        done.releases().stream().filter(r -> r.getServiceName().equals(peer))
                                .findFirst().orElseThrow().getStatus(),
                        peer + " continues to completion after restart");
            }
            // 被恢复成员的 CANARY 在重启后没有被重新部署：
            // CANARY 阶段既无 STAGE_PASSED 也无 STAGE_DEPLOYED，只停在调和出的 FAILED。
            var recoveredJournal = restarted.getBean(
                    com.github.xingjianluo.releaseorchestratorjava.state.EventJournal.class);
            long canaryDeploys = recoveredJournal.readAll().stream()
                    .filter(e -> e.releaseId().equals(crashed.getReleaseId())
                            && "STAGE_DEPLOYED".equals(e.type())
                            && e.detail().contains("stage=CANARY")).count();
            long canaryPassed = recoveredJournal.readAll().stream()
                    .filter(e -> e.releaseId().equals(crashed.getReleaseId())
                            && "STAGE_PASSED".equals(e.type())
                            && e.detail().contains("stage=CANARY")).count();
            assertEquals(0, canaryDeploys, "interrupted CANARY is not re-deployed after recovery");
            assertEquals(0, canaryPassed, "interrupted CANARY never passes after recovery");
            // BUILD/STAGING 的历史部署仍在，证明成员不是被整体重置。
            long earlierDeploys = recoveredJournal.readAll().stream()
                    .filter(e -> e.releaseId().equals(crashed.getReleaseId())
                            && "STAGE_DEPLOYED".equals(e.type())).count();
            assertEquals(1, earlierDeploys, "pre-crash STAGING deployment history survives");
            testLog.info("restart recovery outcomes stable: {}", last.outcomes());
        }
    }

    @Test
    void failureObservedBeforeCrashSurvivesRestartAndIsOnlySkippedAfterwards() {
        // 另一种重启：失败在崩溃前已经正常落盘（非 RUNNING 残留）。
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-restart-2", List.of(
                member("svc-m1", "m-1", null),
                member("svc-m2", "m-2", "fail:PRODUCTION"))));
        groups.advanceGroup(group.groupId()); // BUILD
        groups.advanceGroup(group.groupId()); // STAGING
        groups.advanceGroup(group.groupId()); // CANARY（PRODUCTION 故障不影响前三阶段）
        GroupOperationResult prodSweep = groups.advanceGroup(group.groupId()); // PRODUCTION 失败
        MemberOutcome m2failure = outcomeOf(prodSweep, "svc-m2");
        assertFalse(m2failure.ok());
        assertEquals("PRODUCTION_HEALTH_CHECK_FAILED", m2failure.code());

        try (ConfigurableApplicationContext restarted = restartContext()) {
            ReleaseOrchestrator recoveredReleases = restarted.getBean(ReleaseOrchestrator.class);
            ReleaseGroupOrchestrator recoveredGroups = restarted.getBean(ReleaseGroupOrchestrator.class);
            // 没有残留 RUNNING：恢复为空，不改动任何成员。
            assertEquals(0, recoveredReleases.recoverInterrupted().size());

            GroupOperationResult afterRestart = recoveredGroups.advanceGroup(group.groupId());
            MemberOutcome m2 = outcomeOf(afterRestart, "svc-m2");
            assertFalse(m2.ok());
            assertEquals("SKIPPED", m2.action());
            assertEquals("RELEASE_FAILED", m2.code());
            assertTrue(m2.message().contains("PRODUCTION_HEALTH_CHECK_FAILED"));
            assertEquals(GroupStatus.FAILED, afterRestart.group().status());

            // 结论与同轮返回的成员真实状态一致，不需要再查一次详情才能发现失败。
            Release m2State = afterRestart.group().releases().stream()
                    .filter(r -> r.getServiceName().equals("svc-m2")).findFirst().orElseThrow();
            assertEquals(ReleaseStatus.FAILED, m2State.getStatus());
            assertEquals("PRODUCTION_HEALTH_CHECK_FAILED", m2State.getFailureCode());
            assertEquals(StageName.PRODUCTION, m2State.getFailureStage());
        }
    }
}
