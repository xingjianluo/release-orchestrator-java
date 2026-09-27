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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发与规模：同服务并发只产生一次部署；几十个不同服务并行推进、互不阻塞、状态不串台。 */
public class ReleaseGroupConcurrencyTest extends AbstractOrchestratorTest {

    @Autowired
    private ReleaseGroupOrchestrator groups;

    @Test
    void concurrentIdenticalGroupSubmissionCreatesExactlyOneGroupAndOneDeploymentPerService()
            throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        List<ReleaseGroup> winners = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReleaseGroup g = groups.submit(new ReleaseGroupRequest("concurrent-key", List.of(
                            new GroupMemberRequest("billing-api",
                                    "0123456789abcdef0123456789abcdef01234567", "same", null),
                            new GroupMemberRequest("edge-api",
                                    "0123456789abcdef0123456789abcdef01234567", "same", null))));
                    accepted.incrementAndGet();
                    winners.add(g);
                } catch (ReleaseException e) {
                    if ("DUPLICATE_SUBMISSION".equals(e.getCode())) {
                        duplicate.incrementAndGet();
                    } else {
                        throw e;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(1, accepted.get(), "exactly one concurrent submission wins");
        assertEquals(threads - 1, duplicate.get());

        // 每个服务磁盘上只有一条发布状态
        ReleaseGroup group = winners.get(0);
        for (var member : group.getMembers()) {
            Release r = orchestrator.get(member.getReleaseId());
            assertEquals(group.getGroupId(), r.getGroupId());
        }
        long releaseFiles = java.nio.file.Files.list(dataDir().resolve("releases")).count();
        assertEquals(2, releaseFiles, "one state file per service, no duplicated deployments");
    }

    @Test
    void dozensOfDifferentServicesAdvanceInParallelWithoutCrossContamination() throws Exception {
        int n = 36;
        List<GroupMemberRequest> members = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 服务名、提交、内容都不同
            String service = String.format("svc-%02d", i);
            String commit = String.format("%040x", i + 1L);
            members.add(new GroupMemberRequest(service, commit, "content-of-" + i, null));
        }
        ReleaseGroup group = groups.submit(new ReleaseGroupRequest("bulk-1", members));
        assertEquals(n, group.getMembers().size());

        for (int round = 0; round < 4; round++) {
            List<GroupMemberResult> results = groups.advance(group.getGroupId());
            assertEquals(n, results.size());
            for (GroupMemberResult r : results) {
                assertTrue(r.advanced(), "round " + round + " service " + r.serviceName());
                assertEquals(null, r.errorCode());
            }
        }

        ReleaseGroup done = groups.get(group.getGroupId());
        assertEquals(GroupStatus.COMPLETED, done.getStatus());

        // 每个服务各自的指针、稳定登记册都独立成立，版本互不相同（内容不同）
        for (var member : done.getMembers()) {
            Release r = orchestrator.get(member.getReleaseId());
            assertEquals(ReleaseStatus.COMPLETED, r.getStatus());
            assertTrue(java.nio.file.Files.exists(
                    dataDir().resolve("deployments").resolve(member.getServiceName()).resolve("current.json")));
        }
        long releaseFiles = java.nio.file.Files.list(dataDir().resolve("releases")).count();
        long distinctVersions = done.getMembers().stream().map(m -> m.getVersion()).distinct().count();
        assertEquals(n, releaseFiles);
        assertEquals(n, distinctVersions, "no state cross-talk between services");
    }

    @Test
    void parallelAdvanceRoundDoesNotSkipAnyStage() {
        // 8 个服务并行推进 4 轮：每个发布的每个阶段 STAGE_STARTED 都必须恰好一次
        int n = 8;
        List<GroupMemberRequest> members = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            members.add(new GroupMemberRequest(String.format("p-svc-%02d", i),
                    String.format("%040x", 0xabcL + i), "c" + i, null));
        }
        ReleaseGroup group = groups.submit(new ReleaseGroupRequest("parallel-stages", members));
        for (int round = 0; round < 4; round++) {
            groups.advance(group.getGroupId());
        }
        var allEvents = journal.readAll();
        for (var member : group.getMembers()) {
            long started = allEvents.stream()
                    .filter(e -> member.getReleaseId().equals(e.releaseId())
                            && "STAGE_STARTED".equals(e.type()))
                    .count();
            long deployed = allEvents.stream()
                    .filter(e -> member.getReleaseId().equals(e.releaseId())
                            && "STAGE_DEPLOYED".equals(e.type()))
                    .count();
            assertEquals(4, started, member.getServiceName() + " each stage started exactly once");
            assertEquals(3, deployed, member.getServiceName() + " three post-build stage deploys");
        }
    }
}
