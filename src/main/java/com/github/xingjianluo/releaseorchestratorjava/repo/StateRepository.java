package com.github.xingjianluo.releaseorchestratorjava.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.model.HistoryEntry;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseState;
import com.github.xingjianluo.releaseorchestratorjava.model.ServiceState;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 本地 JSON 状态仓库：发布记录、服务状态（含 append-only 变更历史）。
 *
 * <ul>
 *   <li>每次状态变更都原子写盘（临时文件 + ATOMIC_MOVE），崩溃后重启可恢复；</li>
 *   <li>每服务一把锁，串行化同一服务上的发布/回滚，跨服务互不阻塞；</li>
 *   <li>启动时恢复：IN_PROGRESS 且未达终态的发布解释为 FAILED，ROLLING_BACK 解释为 ROLLED_BACK；
 *       同时清理服务上残留的 activeReleaseId。</li>
 * </ul>
 */
@Component
public class StateRepository {

    private static final Logger log = LoggerFactory.getLogger(StateRepository.class);

    private final Path stateDir;
    private final Path releasesDir;
    private final Path servicesDir;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public StateRepository(@Value("${release.state-dir:target/release-state}") String stateDir,
                           ObjectMapper objectMapper) {
        this.stateDir = Path.of(stateDir);
        this.releasesDir = this.stateDir.resolve("releases");
        this.servicesDir = this.stateDir.resolve("services");
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(releasesDir);
            Files.createDirectories(servicesDir);
        } catch (IOException e) {
            throw new IllegalStateException("初始化状态目录失败: " + stateDir, e);
        }
    }

    public void saveRelease(ReleaseRecord record) {
        atomicWrite(releasesDir.resolve(record.getReleaseId() + ".json"), record);
    }

    public ReleaseRecord findRelease(String releaseId) {
        Path f = releasesDir.resolve(releaseId + ".json");
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(f, StandardCharsets.UTF_8), ReleaseRecord.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取发布记录失败: " + releaseId, e);
        }
    }

    public List<ReleaseRecord> findByService(String service) {
        List<ReleaseRecord> result = new ArrayList<>();
        if (!Files.exists(releasesDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(releasesDir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    ReleaseRecord r = objectMapper.readValue(Files.readString(p, StandardCharsets.UTF_8),
                            ReleaseRecord.class);
                    if (service.equals(r.getService())) {
                        result.add(r);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("读取发布记录失败: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("扫描发布记录失败", e);
        }
        result.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        return result;
    }

    public ServiceState getServiceState(String service) {
        Path f = servicesDir.resolve(service + ".json");
        if (!Files.exists(f)) {
            return ServiceState.empty(service, Instant.now());
        }
        try {
            return objectMapper.readValue(Files.readString(f, StandardCharsets.UTF_8), ServiceState.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取服务状态失败: " + service, e);
        }
    }

    public void saveServiceState(ServiceState state) {
        atomicWrite(servicesDir.resolve(state.getService() + ".json"), state);
    }

    /** 在服务锁内加载状态、执行变更并原子落盘。 */
    public void updateServiceState(String service, Consumer<ServiceState> mutator) {
        withLock(service, () -> {
            ServiceState state = getServiceState(service);
            mutator.accept(state);
            saveServiceState(state);
            return null;
        });
    }

    /** 在服务锁内追加一条变更历史（append-only，不删除、不改写既有条目）。 */
    public void appendHistory(String service, String releaseId, String event, String detail) {
        updateServiceState(service, state -> state.appendHistory(
                new HistoryEntry(state.nextHistorySeq(), Instant.now(), releaseId, event, detail)));
    }

    public void withLock(String service, Runnable action) {
        withLock(service, () -> {
            action.run();
            return null;
        });
    }

    public <T> T withLock(String service, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(service, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }

    /** 进程中断后的启动恢复：给出确定、可解释的终态。 */
    public void recoverInterruptedReleases() {
        Instant now = Instant.now();
        List<ReleaseRecord> all = new ArrayList<>();
        try (Stream<Path> stream = Files.list(releasesDir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    all.add(objectMapper.readValue(Files.readString(p, StandardCharsets.UTF_8),
                            ReleaseRecord.class));
                } catch (IOException e) {
                    throw new IllegalStateException("恢复时读取发布记录失败: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("恢复时扫描发布记录失败", e);
        }
        for (ReleaseRecord r : all) {
            if (r.getState() == ReleaseState.PENDING) {
                r.recoverPendingAsFailed(now);
                saveRelease(r);
                log.warn("恢复：发布 {} 在 BUILD 阶段中断，标记为 FAILED", r.getReleaseId());
            } else if (r.getState() == ReleaseState.IN_PROGRESS) {
                r.recoverAsFailed(now);
                saveRelease(r);
                log.warn("恢复：发布 {} 中断于阶段 {}，标记为 FAILED", r.getReleaseId(), r.getFailureStage());
            } else if (r.getState() == ReleaseState.ROLLING_BACK) {
                r.recoverAsRolledBack(now);
                saveRelease(r);
                log.warn("恢复：发布 {} 回滚中断，按已完成处理为 ROLLED_BACK", r.getReleaseId());
            }
        }
        // 清理服务上残留的占用标记；对未上线即中断的发布登记待回滚，保证可执行回滚。
        for (ReleaseRecord r : all) {
            String service = r.getService();
            withLock(service, () -> {
                ServiceState state = getServiceState(service);
                boolean changed = false;
                if (state.getActiveReleaseId() != null) {
                    log.warn("恢复：清理服务 {} 残留的 activeReleaseId={}", service, state.getActiveReleaseId());
                    state.setActiveReleaseId(null);
                    changed = true;
                }
                if (r.getState() == ReleaseState.FAILED
                        && !r.isStageCompleted(com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStage.FULL)
                        && !r.getReleaseId().equals(state.getCurrentReleaseId())
                        && state.getPendingRollbackReleaseId() == null) {
                    state.setPendingRollbackReleaseId(r.getReleaseId());
                    state.appendHistory(new HistoryEntry(state.nextHistorySeq(), now,
                            r.getReleaseId(), "RECOVERED", "进程中断恢复：登记为待回滚的失败发布"));
                    log.warn("恢复：服务 {} 登记待回滚发布 {}", service, r.getReleaseId());
                    changed = true;
                }
                if (changed) {
                    state.setUpdatedAt(now);
                    saveServiceState(state);
                }
            });
        }
    }

    private void atomicWrite(Path file, Object value) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(value), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("原子写入失败: " + file, e);
        }
    }
}
