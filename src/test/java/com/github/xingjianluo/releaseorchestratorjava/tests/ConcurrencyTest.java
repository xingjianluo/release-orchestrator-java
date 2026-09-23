package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发触发、重复推进下的一致性：无重复部署、无跳阶段、结论一致。 */
class ConcurrencyTest extends AbstractOrchestratorTest {

    private ReleaseRequest request(String commit, String content, String fault) {
        return new ReleaseRequest("search-api", commit, content, null, fault);
    }

    @Test
    void concurrentSubmissionsOfSameInputResultInExactlyOneRelease() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();

        var req = request("abcdef0123456789abcdef0123456789abcdef01", "hot-content", null);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    orchestrator.submit(req);
                    accepted.incrementAndGet();
                } catch (ReleaseException e) {
                    if ("DUPLICATE_SUBMISSION".equals(e.getCode())) {
                        duplicate.incrementAndGet();
                    } else if ("RELEASE_BUSY".equals(e.getCode())) {
                        busy.incrementAndGet();
                    } else {
                        throw e;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, accepted.get(), "exactly one submission may win");
        assertEquals(threads - 1, duplicate.get() + busy.get());
        testLog.info("final state: concurrent submit accepted={} duplicate={} busy={}",
                accepted.get(), duplicate.get(), busy.get());
    }

    @Test
    void concurrentAdvancesAdvanceEachStageExactlyOnce() throws Exception {
        Release r = orchestrator.submit(
                request("fedcba9876543210fedcba9876543210fedcba98", "steady-content", null));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger completedRejected = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Release result = orchestrator.advance(r.getReleaseId());
                    if (result.getStatus() == ReleaseStatus.COMPLETED) {
                        ok.incrementAndGet();
                    }
                } catch (ReleaseException e) {
                    switch (e.getCode()) {
                        case "RELEASE_ALREADY_COMPLETED" -> completedRejected.incrementAndGet();
                        case "RELEASE_BUSY" -> busy.incrementAndGet();
                        default -> other.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "all threads finish");

        Release finalState = orchestrator.get(r.getReleaseId());
        assertEquals(0, other.get(), "no unexpected error codes under concurrency");
        assertEquals(ReleaseStatus.COMPLETED, finalState.getStatus());
        for (StageName stage : StageName.ordered()) {
            assertEquals(StageStatus.PASSED, finalState.getStages().get(stage).getStatus(),
                    stage + " must be PASSED, never skipped");
        }
        long startEvents = journal.readAll().stream()
                .filter(e -> "STAGE_STARTED".equals(e.type())).count();
        long deployEvents = journal.readAll().stream()
                .filter(e -> "STAGE_DEPLOYED".equals(e.type())).count();
        assertEquals(4, startEvents, "each stage started exactly once");
        assertEquals(3, deployEvents, "each deploy stage executed exactly once");
        testLog.info("final state: concurrent advance completed={} rejected={} busy={} startEvents={} deployEvents={}",
                ok.get(), completedRejected.get(), busy.get(), startEvents, deployEvents);
    }
}
