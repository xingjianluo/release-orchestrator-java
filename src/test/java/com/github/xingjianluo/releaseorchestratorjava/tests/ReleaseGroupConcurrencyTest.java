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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布组的并发、幂等与规模：同组并发提交只建一次；几十服务并行推进不串台。 */
class ReleaseGroupConcurrencyTest extends AbstractOrchestratorTest {

    private static final String COMMIT = "2222222789abcdef0123456789abcdef01234567";

    @Autowired
    private ReleaseGroupOrchestrator groups;

    private ReleaseRequest member(String service, String content) {
        return new ReleaseRequest(service, COMMIT, content, null, null);
    }

    private ReleaseRequest failingMember(String service, String content, String stage) {
        return new ReleaseRequest(service, COMMIT, content, null, "fail:" + stage);
    }

    private static MemberOutcome outcomeOf(GroupOperationResult result, String service) {
        return result.outcomes().stream()
                .filter(o -> o.serviceName().equals(service)).findFirst().orElseThrow();
    }

    private ReleaseGroupRequest batch(String key, int size) {
        List<ReleaseRequest> members = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            members.add(member(String.format("svc-%02d", i), "content-" + i));
        }
        return new ReleaseGroupRequest(key, members);
    }

    @Test
    void concurrentSubmissionsOfSameGroupCreateExactlyOneGroup() throws Exception {
        ReleaseGroupRequest request = batch("ticket-concurrent", 6);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> groupIds = ConcurrentHashMap.newKeySet();
        AtomicInteger busyOrConflict = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    groupIds.add(groups.submitGroup(request).groupId());
                } catch (com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException e) {
                    // 并发下同组提交只允许“成功返回同一组”或“忙”，绝不允许建出第二个组。
                    assertEquals("GROUP_BUSY", e.getCode());
                    busyOrConflict.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, groupIds.size(), "all concurrent submitters converge on one group identity");
        assertEquals(1, groups.listGroups().size(), "exactly one group persisted");
        assertEquals(6, orchestrator.list().size(), "exactly one release per member, no duplicates");
        testLog.info("final state: concurrent group submit threads={} busy={} groups={} releases={}",
                threads, busyOrConflict.get(), groups.listGroups().size(), orchestrator.list().size());
    }

    @Test
    void batchOfThirtyServicesAdvancesInParallelWithoutCrossTalk() throws Exception {
        int size = 30;
        ReleaseGroupView group = groups.submitGroup(batch("ticket-scale", size));
        assertEquals(GroupStatus.PENDING, group.status());

        long started = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            groups.advanceGroup(group.groupId());
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        ReleaseGroupView done = groups.getGroup(group.groupId());
        assertEquals(GroupStatus.COMPLETED, done.status());
        assertEquals(size, done.releases().size());
        for (Release r : done.releases()) {
            assertEquals(ReleaseStatus.COMPLETED, r.getStatus());
            for (StageName stage : StageName.ordered()) {
                assertEquals(StageStatus.PASSED, r.getStages().get(stage).getStatus(),
                        r.getServiceName() + " stage " + stage + " passed exactly once");
            }
            // 无串台：发布上的服务名、内容哈希、制品路径都属于它自己。
            int index = Integer.parseInt(r.getServiceName().substring("svc-".length()));
            String expectedHash = com.github.xingjianluo.releaseorchestratorjava
                    .version.DefaultVersionCalculator.sha256Hex(
                            ("content-" + index).getBytes());
            assertEquals(expectedHash, r.getContentHash(),
                    "member content must not be mixed up across services");
            assertTrue(r.getArtifactUri().contains("artifacts/" + r.getServiceName() + "/"));
            assertTrue(Files.isRegularFile(
                    dataDir().resolve("deployments").resolve(r.getServiceName()).resolve("current.json")));
        }
        // 每个成员每个阶段恰好执行一次：不多发、不跳阶段。
        long stageStarts = journal.readAll().stream()
                .filter(e -> "STAGE_STARTED".equals(e.type())).count();
        long deploys = journal.readAll().stream()
                .filter(e -> "STAGE_DEPLOYED".equals(e.type())).count();
        assertEquals(size * 4L, stageStarts, "each member runs each stage exactly once");
        assertEquals(size * 3L, deploys, "each member deploys exactly once per deploy stage");
        testLog.info("final state: {} services completed in {} ms (parallelism={}, linear-ish scaling)",
                size, elapsedMs, properties.getGroupParallelism());
    }

    @Test
    void concurrentGroupSweepsKeepExactlyOnceStageSemantics() throws Exception {
        int size = 10;
        ReleaseGroupView group = groups.submitGroup(batch("ticket-sweeps", size));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sweeps = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 多线程并发扫组，直到组完成；单成员锁保证阶段恰好执行一次。
                    for (int iter = 0; iter < 60; iter++) {
                        var result = groups.advanceGroup(group.groupId());
                        sweeps.incrementAndGet();
                        if (result.group().status() == GroupStatus.COMPLETED) {
                            return;
                        }
                    }
                    throw new IllegalStateException("group did not complete within sweep budget");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        ReleaseGroupView done = groups.getGroup(group.groupId());
        assertEquals(GroupStatus.COMPLETED, done.status());
        long stageStarts = journal.readAll().stream()
                .filter(e -> "STAGE_STARTED".equals(e.type())).count();
        assertEquals(size * 4L, stageStarts,
                "concurrent sweeps must not double-run or skip any stage");
        testLog.info("final state: {} concurrent sweepers, {} sweeps, stage executions exactly {}",
                threads, sweeps.get(), size * 4L);
    }

    @Test
    void concurrentSweepsStabilizeFailedMemberOutcomeWithoutOverwritingOrReRunning() throws Exception {
        // 8 个健康成员 + 1 个必在 CANARY 失败的成员，多线程同时反复推进。
        int healthy = 8;
        List<ReleaseRequest> members = new ArrayList<>();
        for (int i = 0; i < healthy; i++) {
            members.add(member(String.format("svc-h%02d", i), "healthy-content-" + i));
        }
        members.add(failingMember("svc-loser", "loser-content", "CANARY"));
        ReleaseGroupView group = groups.submitGroup(
                new ReleaseGroupRequest("ticket-concurrent-failure", members));
        String loserId = group.releases().stream()
                .filter(r -> r.getServiceName().equals("svc-loser")).findFirst().orElseThrow()
                .getReleaseId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        // 收集所有扫描中针对 svc-loser 的结论。要求：
        //   - 恰好一次“失败当轮”结论 ADVANCE/ok=false/CANARY_HEALTH_CHECK_FAILED；
        //   - ok=true 只允许出现在它之前（BUILD/STAGING 的真实成功），
        //     失败落盘后任何一次扫描都不得再把它记成成功；
        //   - 之后只能是 SKIPPED/RELEASE_FAILED 且保留原始原因。
        List<MemberOutcome> loserOutcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger groupBusy = new AtomicInteger();
        AtomicInteger sweeps = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int skipsAfterTerminal = 0;
                    for (int iter = 0; iter < 80; iter++) {
                        GroupOperationResult result;
                        try {
                            result = groups.advanceGroup(group.groupId());
                        } catch (com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException e) {
                            // 并发下拿组锁失败是允许的响应之一，但必须是 GROUP_BUSY。
                            assertEquals("GROUP_BUSY", e.getCode());
                            groupBusy.incrementAndGet();
                            continue;
                        }
                        sweeps.incrementAndGet();
                        MemberOutcome loserOutcome = outcomeOf(result, "svc-loser");
                        loserOutcomes.add(loserOutcome);
                        boolean peersDone = result.group().releases().stream()
                                .filter(r -> !r.getServiceName().equals("svc-loser"))
                                .allMatch(r -> r.getStatus() == ReleaseStatus.COMPLETED);
                        // 健康成员全部完成且失败成员已稳定跳过后，每个线程再采样两轮才退出，
                        // 确保终态后的跳过结论也被并发采集到。
                        if (peersDone && "SKIPPED".equals(loserOutcome.action())
                                && ++skipsAfterTerminal >= 2) {
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(sweeps.get() >= 4, "sweeps actually ran: " + sweeps.get());

        // 组锁把扫描串行化，因此 svc-loser 的每轮结论是确定性序列：
        // BUILD 成功、STAGING 成功（各恰好一次）→ CANARY 失败（恰好一次）→ 之后永远跳过。
        List<MemberOutcome> successes = loserOutcomes.stream()
                .filter(MemberOutcome::ok).toList();
        assertEquals(2, successes.size(),
                "only BUILD and STAGING succeed; the failed stage is never reported success: "
                        + loserOutcomes);
        for (MemberOutcome success : successes) {
            assertEquals("ADVANCE", success.action());
        }

        // 1) 失败当轮结论：ADVANCE/ok=false/原始错误码，恰好一次（CANARY 没有被重跑）。
        List<MemberOutcome> inlineFailures = loserOutcomes.stream()
                .filter(o -> !o.ok() && "ADVANCE".equals(o.action()))
                .filter(o -> "CANARY_HEALTH_CHECK_FAILED".equals(o.code())).toList();
        assertEquals(1, inlineFailures.size(),
                "the failing round reports the failure exactly once: " + loserOutcomes);

        // 2) 失败终态后的结论全部是 SKIPPED/RELEASE_FAILED 且保留原因；
        //    所有扫描结论数量闭合：成功 2 + 失败 1 + 跳过 N = 总扫描数。
        List<MemberOutcome> skips = loserOutcomes.stream()
                .filter(o -> "SKIPPED".equals(o.action())).toList();
        assertEquals(loserOutcomes.size() - 3L, skips.size(),
                "every remaining sweep reports the terminal-failed member as skipped");
        assertTrue(skips.size() >= 1, "terminal-failed member reported skipped on later sweeps");
        for (MemberOutcome skip : skips) {
            assertEquals("RELEASE_FAILED", skip.code(), skip.toString());
            assertTrue(skip.message().contains("CANARY_HEALTH_CHECK_FAILED"),
                    "skip retains original failure reason: " + skip.message());
        }

        // 3) 终态持久化稳定：失败码不被并发扫描覆盖。
        Release loser = orchestrator.get(loserId);
        assertEquals(ReleaseStatus.FAILED, loser.getStatus());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", loser.getFailureCode());
        assertEquals(StageName.CANARY, loser.getFailureStage());

        // 4) 健康成员：四阶段恰好执行一次，全部完成，状态不串台。
        ReleaseGroupView done = groups.getGroup(group.groupId());
        assertEquals(GroupStatus.FAILED, done.status(),
                "group status consistent with the failed member under concurrency");
        for (Release r : done.releases()) {
            if (r.getServiceName().equals("svc-loser")) {
                continue;
            }
            assertEquals(ReleaseStatus.COMPLETED, r.getStatus(), r.getServiceName() + " completes");
            for (StageName stage : StageName.ordered()) {
                assertEquals(StageStatus.PASSED, r.getStages().get(stage).getStatus(),
                        r.getServiceName() + " stage " + stage + " passed exactly once");
            }
        }
        long loserCanaryStarts = journal.readAll().stream()
                .filter(e -> e.releaseId().equals(loserId))
                .filter(e -> "STAGE_STARTED".equals(e.type()) && e.detail().contains("stage=CANARY"))
                .count();
        assertEquals(1L, loserCanaryStarts, "loser CANARY never re-run by concurrent sweeps");
        long peerStageStarts = journal.readAll().stream()
                .filter(e -> "STAGE_STARTED".equals(e.type()))
                .filter(e -> !e.releaseId().equals(loserId)).count();
        assertEquals(healthy * 4L, peerStageStarts, "healthy members run each stage exactly once");
        testLog.info("final state: concurrent sweeps={} groupBusy={} inlineFailures={} skips={} loser stable FAILED",
                sweeps.get(), groupBusy.get(), inlineFailures.size(), skips.size());
    }
}
