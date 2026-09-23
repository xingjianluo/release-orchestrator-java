package com.github.xingjianluo.releaseorchestratorjava.version;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于文件的稳定版本登记册：registry/{serviceName}.txt，每行一个已 COMPLETED 的版本，
 * 按成功顺序保留。只有完整通过全部阶段的版本才会登记，因此始终是回滚的可信目标来源。
 */
@Component
public class FileVersionRegistry implements VersionRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileVersionRegistry.class);

    private final OrchestratorProperties properties;

    public FileVersionRegistry(OrchestratorProperties properties) {
        this.properties = properties;
    }

    private Path registryDir() {
        return properties.dataPath().resolve("registry");
    }

    @Override
    public void registerStable(String serviceName, String version) {
        try {
            Files.createDirectories(registryDir());
            Path file = registryDir().resolve(serviceName + ".txt");
            Path lockFile = registryDir().resolve(serviceName + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                List<String> versions = readLines(file);
                if (!versions.contains(version)) {
                    Files.writeString(file, version + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    log.info("stable version registered service={} version={} (stableCount={})",
                            serviceName, version, versions.size() + 1);
                } else {
                    log.info("stable version registration is idempotent service={} version={}",
                            serviceName, version);
                }
            }
        } catch (IOException e) {
            throw new ReleaseException("REGISTRY_IO_ERROR",
                    "failed to register stable version " + serviceName + "@" + version + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> stableVersions(String serviceName) {
        return readLines(registryDir().resolve(serviceName + ".txt"));
    }

    @Override
    public String previousStable(String serviceName, String currentVersion) {
        List<String> versions = stableVersions(serviceName);
        String previous = null;
        for (String v : versions) {
            if (v.equals(currentVersion)) {
                return previous;
            }
            previous = v;
        }
        // 当前版本尚未登记（失败发布）：最后一个稳定版本即为回滚目标。
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    private List<String> readLines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            Set<String> unique = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    unique.add(line.trim());
                }
            }
            return new ArrayList<>(unique);
        } catch (IOException e) {
            throw new ReleaseException("REGISTRY_IO_ERROR",
                    "failed to read stable versions from " + file + ": " + e.getMessage(), e);
        }
    }
}
