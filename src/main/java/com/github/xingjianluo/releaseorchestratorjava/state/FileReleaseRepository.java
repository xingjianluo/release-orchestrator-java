package com.github.xingjianluo.releaseorchestratorjava.state;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 文件系统发布存储：
 * <ul>
 *   <li>每个发布一个 JSON：releases/{releaseId}.json，先写 tmp 再原子替换；</li>
 *   <li>{@link #lock} 同时获取进程内锁与 .lock 文件锁，串行化并发推进；</li>
 *   <li>损坏 JSON 抛 STATE_CORRUPT，绝不静默重建（历史不丢失/错乱）。</li>
 * </ul>
 */
@Component
public class FileReleaseRepository implements ReleaseRepository {

    private static final Logger log = LoggerFactory.getLogger(FileReleaseRepository.class);

    private final ObjectMapper mapper;
    private final OrchestratorProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> inProcessLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileChannel> heldChannels = new ConcurrentHashMap<>();

    public FileReleaseRepository(OrchestratorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    private Path releasesDir() {
        return properties.dataPath().resolve("releases");
    }

    private Path locksDir() {
        return properties.dataPath().resolve("locks");
    }

    @Override
    public void save(Release release) {
        try {
            Files.createDirectories(releasesDir());
            Path target = releasesDir().resolve(release.getReleaseId() + ".json");
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(release);
            Files.write(tmp, json);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("release state saved id={} status={}", release.getReleaseId(), release.getStatus());
        } catch (IOException e) {
            throw new ReleaseException("STATE_IO_ERROR",
                    "failed to save release " + release.getReleaseId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Release> find(String releaseId) {
        Path target = releasesDir().resolve(releaseId + ".json");
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(read(target));
    }

    @Override
    public List<Release> findAll() {
        if (!Files.isDirectory(releasesDir())) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(releasesDir())) {
            return paths.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new ReleaseException("STATE_IO_ERROR", "failed to list releases: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Release> findByContent(String serviceName, String commit, String contentHash) {
        return findAll().stream()
                .filter(r -> serviceName.equals(r.getServiceName())
                        && commit.equals(r.getCommit())
                        && contentHash.equals(r.getContentHash()))
                .findFirst();
    }

    @Override
    public boolean lock(String releaseId, long timeoutMillis) {
        ReentrantLock reentrantLock = inProcessLocks.computeIfAbsent(releaseId, k -> new ReentrantLock(true));
        boolean reentrant = reentrantLock.isHeldByCurrentThread();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            if (!reentrantLock.tryLock(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS)) {
                return false;
            }
            if (reentrant) {
                // 同线程重入：文件锁仍由首次持有者掌握，直接成功。
                return true;
            }
            Files.createDirectories(locksDir());
            Path lockPath = locksDir().resolve(releaseId + ".lock");
            FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = null;
            while (fileLock == null) {
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException ignore) {
                    fileLock = null;
                }
                if (fileLock == null) {
                    if (System.currentTimeMillis() >= deadline) {
                        channel.close();
                        reentrantLock.unlock();
                        return false;
                    }
                    Thread.sleep(20);
                }
            }
            heldChannels.put(releaseId, channel);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReleaseException("LOCK_INTERRUPTED", "interrupted while acquiring lock " + releaseId, e);
        } catch (IOException e) {
            reentrantLock.unlock();
            throw new ReleaseException("LOCK_IO_ERROR", "failed to acquire lock " + releaseId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void unlock(String releaseId) {
        ReentrantLock reentrantLock = inProcessLocks.get(releaseId);
        if (reentrantLock == null || !reentrantLock.isHeldByCurrentThread()) {
            return;
        }
        boolean lastHold = reentrantLock.getHoldCount() == 1;
        if (lastHold) {
            FileChannel channel = heldChannels.remove(releaseId);
            if (channel != null) {
                try {
                    channel.close(); // 同时释放文件锁
                } catch (IOException e) {
                    log.warn("failed to close lock channel for {}: {}", releaseId, e.getMessage());
                }
            }
        }
        reentrantLock.unlock();
    }

    private Release read(Path path) {
        try {
            return mapper.readValue(Files.readAllBytes(path), Release.class);
        } catch (IOException | UncheckedIOException e) {
            throw new ReleaseException("STATE_CORRUPT",
                    "release state file is corrupt and will not be rebuilt: " + path + " (" + e.getMessage() + ")", e);
        }
    }
}
