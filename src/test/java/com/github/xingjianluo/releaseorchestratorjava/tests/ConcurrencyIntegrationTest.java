package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.releaseorchestratorjava.error.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseState;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.repo.StateRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @BeforeAll
    static void initDir() throws Exception {
        initWorkDir(ConcurrencyIntegrationTest.class);
    }

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIntegrationTest.class);

    @Autowired
    private ReleaseOrchestrator orchestrator;
    @Autowired
    private StateRepository repository;

    @Test
    void concurrentDuplicateSubmissionsYieldExactlyOneRelease() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<String> rejectedCodes = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReleaseRequest req = request("wallet", "1.0.0", "ref-1", List.of("n1", "n2"), null);
                    orchestrator.submit(req);
                    success.incrementAndGet();
                } catch (ReleaseException e) {
                    rejectedCodes.add(e.getErrorCode().name());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        log.info("并发提交结果: 成功={} 拒绝={} 拒绝原因分布={}",
                success.get(), rejectedCodes.size(),
                rejectedCodes.stream().collect(Collectors.groupingBy(c -> c, Collectors.counting())));

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejectedCodes).hasSize(threads - 1);
        assertThat(rejectedCodes).allMatch(c -> c.equals(ErrorCode.DUPLICATE_RELEASE.name()));
        List<ReleaseRecord> records = repository.findByService("wallet");
        assertThat(records).hasSize(1);
        // 只有一次 BUILD 部署
        assertThat(records.get(0).getCompletedStages()).hasSize(1);
    }

    @Test
    void concurrentAdvanceCallsDoNotSkipOrDuplicateStages() throws Exception {
        ReleaseRecord initial = orchestrator.submit(
                request("ledger", "3.1.0", "ref-9", List.of(), null));
        String id = initial.getReleaseId();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger advanced = new AtomicInteger();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    orchestrator.advance(id);
                    advanced.incrementAndGet();
                } catch (ReleaseException e) {
                    errors.add(e.getErrorCode().name());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        ReleaseRecord finalRecord = orchestrator.get(id);
        log.info("并发推进结果: 成功推进次数={} 拒绝次数={} 最终状态={} 已完成阶段={} 错误分布={}",
                advanced.get(), errors.size(), finalRecord.getState(),
                finalRecord.getCompletedStages(),
                errors.stream().collect(Collectors.groupingBy(c -> c, Collectors.counting())));

        // BUILD 已在 submit 完成；剩余 STAGING/CANARY/FULL 三个阶段恰好各推进一次
        assertThat(advanced.get()).isEqualTo(3);
        assertThat(finalRecord.getState()).isEqualTo(ReleaseState.STABLE);
        assertThat(finalRecord.getCompletedStages()).hasSize(4);
        // 多余推进被拒绝为“不可推进”，而不是跳过阶段或重复部署
        assertThat(errors).hasSize(threads - 3);
        assertThat(errors).allMatch(ErrorCode.RELEASE_NOT_ADVANCEABLE.name()::equals);
    }
}
