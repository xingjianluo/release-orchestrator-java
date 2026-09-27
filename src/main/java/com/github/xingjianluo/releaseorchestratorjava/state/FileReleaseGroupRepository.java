package com.github.xingjianluo.releaseorchestratorjava.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroup;
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
 * 文件系统发布组存储：
 * <ul>
 *   <li>每个组一个 JSON：groups/{groupId}.json，先写 tmp 再原子替换；</li>
 *   <li>{@link #lock} 同时获取进程内锁与 .lock 文件锁，保证并发/重复提交组时
 *       “判定是否存在 → 建组”是临界区，不会建出两个组；</li>
 *   <li>损坏 JSON 抛 STATE_CORRUPT，绝不静默重建。</li>
 * </ul>
 */
@Component
public class FileReleaseGroupRepository implements ReleaseGroupRepository {

    private static final Logger log = LoggerFactory.getLogger(FileReleaseGroupRepository.class);

    private final ObjectMapper mapper;
    private final OrchestratorProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> inProcessLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileChannel> heldChannels = new ConcurrentHashMap<>();

    public FileReleaseGroupRepository(OrchestratorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    private Path groupsDir() {
        return properties.dataPath().resolve("groups");
    }

    private Path locksDir() {
        return properties.dataPath().resolve("locks");
    }

    @Override
    public void save(ReleaseGroup group) {
        try {
            Files.createDirectories(groupsDir());
            Path target = groupsDir().resolve(group.getGroupId() + ".json");
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(group);
            Files.write(tmp, json);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("release group saved id={} members={}", group.getGroupId(), group.getMembers().size());
        } catch (IOException e) {
            throw new ReleaseException("STATE_IO_ERROR",
                    "failed to save release group " + group.getGroupId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ReleaseGroup> find(String groupId) {
        Path target = groupsDir().resolve(groupId + ".json");
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(read(target));
    }

    @Override
    public List<ReleaseGroup> findAll() {
        if (!Files.isDirectory(groupsDir())) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(groupsDir())) {
            return paths.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new ReleaseException("STATE_IO_ERROR", "failed to list release groups: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean lock(String groupId, long timeoutMillis) {
        ReentrantLock reentrantLock = inProcessLocks.computeIfAbsent(groupId, k -> new ReentrantLock(true));
        boolean reentrant = reentrantLock.isHeldByCurrentThread();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            if (!reentrantLock.tryLock(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS)) {
                return false;
            }
            if (reentrant) {
                return true;
            }
            Files.createDirectories(locksDir());
            Path lockPath = locksDir().resolve(groupId + ".lock");
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
            heldChannels.put(groupId, channel);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReleaseException("LOCK_INTERRUPTED", "interrupted while acquiring lock " + groupId, e);
        } catch (IOException e) {
            reentrantLock.unlock();
            throw new ReleaseException("LOCK_IO_ERROR", "failed to acquire lock " + groupId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void unlock(String groupId) {
        ReentrantLock reentrantLock = inProcessLocks.get(groupId);
        if (reentrantLock == null || !reentrantLock.isHeldByCurrentThread()) {
            return;
        }
        boolean lastHold = reentrantLock.getHoldCount() == 1;
        if (lastHold) {
            FileChannel channel = heldChannels.remove(groupId);
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    log.warn("failed to close lock channel for {}: {}", groupId, e.getMessage());
                }
            }
        }
        reentrantLock.unlock();
    }

    private ReleaseGroup read(Path path) {
        try {
            return mapper.readValue(Files.readAllBytes(path), ReleaseGroup.class);
        } catch (IOException | UncheckedIOException e) {
            throw new ReleaseException("STATE_CORRUPT",
                    "release group state file is corrupt and will not be rebuilt: "
                            + path + " (" + e.getMessage() + ")", e);
        }
    }
}
