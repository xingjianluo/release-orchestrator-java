package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.release.ReleaseGroupOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同服务被“组提交”和“单服务提交”并发触发时，全局只能产生一次部署。 */
public class ReleaseGroupCrossTriggerConcurrencyTest extends AbstractOrchestratorTest {

    @Autowired
    private ReleaseGroupOrchestrator groups;

    @Test
    void groupAndStandaloneSubmitForSameServiceProduceExactlyOneRelease() throws Exception {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        String content = "race-content";
        int perSide = 8;
        ExecutorService pool = Executors.newFixedThreadPool(perSide * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < perSide; i++) {
            pool.submit(() -> race(start, success, duplicate, other, true, commit, content));
            pool.submit(() -> race(start, success, duplicate, other, false, commit, content));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "all racing threads finish");

        assertEquals(1, success.get(), "exactly one deployment is created under concurrent triggers");
        assertEquals(perSide * 2 - 1, duplicate.get(),
                "every other trigger is rejected as DUPLICATE_SUBMISSION, no extra errors");
        assertEquals(0, other.get());

        long files = java.nio.file.Files.list(dataDir().resolve("releases")).count();
        assertEquals(1, files, "only one release state file must exist");
    }

    private void race(CountDownLatch start, AtomicInteger success, AtomicInteger duplicate,
                      AtomicInteger other, boolean viaGroup, String commit, String content) {
        try {
            start.await();
            if (viaGroup) {
                ReleaseGroup g = groups.submit(new ReleaseGroupRequest("cross-key", List.of(
                        new GroupMemberRequest("billing-api", commit, content, null))));
                success.incrementAndGet();
            } else {
                Release r = orchestrator.submit(
                        new ReleaseRequest("billing-api", commit, content, "cross-standalone", null));
                success.incrementAndGet();
            }
        } catch (ReleaseException e) {
            if ("DUPLICATE_SUBMISSION".equals(e.getCode())) {
                duplicate.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            other.incrementAndGet();
        }
    }
}
