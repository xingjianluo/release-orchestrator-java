package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.repo.StateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时执行崩溃恢复，使进程中断后的发布进入确定、可解释的终态。 */
@Component
public class RecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunner.class);

    private final StateRepository repository;

    public RecoveryRunner(StateRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动恢复：扫描未决发布并解析为确定终态");
        repository.recoverInterruptedReleases();
        log.info("启动恢复完成");
    }
}
