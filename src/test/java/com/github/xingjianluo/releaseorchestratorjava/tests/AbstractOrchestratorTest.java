package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.state.EventJournal;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/**
 * 测试基类：每个测试类使用独立的本地临时目录（离线、无外部服务），
 * 每个用例前清空，保证可重复运行。
 */
@SpringBootTest
public abstract class AbstractOrchestratorTest {

    protected final Logger testLog = LoggerFactory.getLogger(getClass());

    private static final Path DATA_DIR = Path.of("target", "test-data",
            UUID.randomUUID().toString().substring(0, 8));

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("release.orchestrator.data-dir", () -> DATA_DIR.toAbsolutePath().toString());
        registry.add("release.orchestrator.recover-on-startup", () -> "false");
    }

    @Autowired
    protected ReleaseOrchestrator orchestrator;

    @Autowired
    protected EventJournal journal;

    @Autowired
    protected OrchestratorProperties properties;

    @BeforeEach
    void cleanDataDir() throws IOException {
        if (Files.exists(DATA_DIR)) {
            try (var paths = Files.walk(DATA_DIR)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        testLog.info("=== test input: clean data dir {} ===", DATA_DIR);
    }

    protected Path dataDir() {
        return DATA_DIR;
    }
}
