package com.github.xingjianluo.releaseorchestratorjava.state;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内 {@link ReentrantLock} + JVM 文件锁的两段式锁管理器，按任意 key 加锁。
 * <p>发布（key = releaseId）与发布组（key = "group:" + groupId）共用同一张锁表：
 * <ul>
 *   <li>同一发布/同一组的并发触发被串行化；</li>
 *   <li>不同发布/不同组的 key 不同，互不阻塞；</li>
 *   <li>锁文件统一落在 locks/ 下，key 仅含安全字符（调用方负责）。</li>
 * </ul>
 */
@Component
public class StateLockManager {

    private static final Logger log = LoggerFactory.getLogger(StateLockManager.class);

    private final OrchestratorProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> inProcessLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileChannel> heldChannels = new ConcurrentHashMap<>();

    public StateLockManager(OrchestratorProperties properties) {
        this.properties = properties;
    }

    private Path locksDir() {
        return properties.dataPath().resolve("locks");
    }

    /**
     * 获取 key 对应的可重入锁（进程内公平锁 + 文件锁）。超时拿不到返回 false。
     * 同线程重入直接成功（文件锁仍由首次持有者掌握）。
     */
    public boolean lock(String key, long timeoutMillis) {
        ReentrantLock reentrantLock = inProcessLocks.computeIfAbsent(key, k -> new ReentrantLock(true));
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
            Path lockPath = locksDir().resolve(key + ".lock");
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
            heldChannels.put(key, channel);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReleaseException("LOCK_INTERRUPTED", "interrupted while acquiring lock " + key, e);
        } catch (IOException e) {
            reentrantLock.unlock();
            throw new ReleaseException("LOCK_IO_ERROR", "failed to acquire lock " + key + ": " + e.getMessage(), e);
        }
    }

    public void unlock(String key) {
        ReentrantLock reentrantLock = inProcessLocks.get(key);
        if (reentrantLock == null || !reentrantLock.isHeldByCurrentThread()) {
            return;
        }
        boolean lastHold = reentrantLock.getHoldCount() == 1;
        if (lastHold) {
            FileChannel channel = heldChannels.remove(key);
            if (channel != null) {
                try {
                    channel.close(); // 同时释放文件锁
                } catch (IOException e) {
                    log.warn("failed to close lock channel for {}: {}", key, e.getMessage());
                }
            }
        }
        reentrantLock.unlock();
    }
}
