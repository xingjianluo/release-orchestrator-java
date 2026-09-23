package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 集成测试基类：每个测试类一个独立、确定路径的本地工作目录，完全离线、可复现。
 *
 * <p>不使用静态 @TempDir：它在 Spring 上下文缓存（多个集成测试类复用同一上下文）下会被
 * 提前清理，而动态属性只注册一次，导致后续类写入已删除目录。这里用 target/test-workdir 下
 * 以测试类命名的确定性目录，{@code @BeforeAll} 清理重建。</p>
 */
abstract class AbstractIntegrationTest {

    protected static Path workDir;

    protected static void initWorkDir(Class<?> testClass) throws IOException {
        workDir = Path.of("target", "test-workdir", testClass.getSimpleName());
        if (Files.exists(workDir)) {
            try (Stream<Path> walk = Files.walk(workDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(workDir.resolve("state"));
        Files.createDirectories(workDir.resolve("artifacts"));
    }

    @DynamicPropertySource
    static void stateProperties(DynamicPropertyRegistry registry) {
        registry.add("release.state-dir", () -> workDir.resolve("state").toString());
        registry.add("release.artifact-dir", () -> workDir.resolve("artifacts").toString());
    }

    static ReleaseRequest request(String service, String version, String ref, List<String> notes,
                                  String failAt) {
        ReleaseRequest r = new ReleaseRequest();
        r.setService(service);
        r.setVersion(version);
        r.setSourceRef(ref);
        r.setChangeNotes(notes);
        r.setFailAtStage(failAt);
        return r;
    }
}
