package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.GroupStatus;
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
}
