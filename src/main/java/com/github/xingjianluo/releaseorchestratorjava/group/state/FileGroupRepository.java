package com.github.xingjianluo.releaseorchestratorjava.group.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.xingjianluo.releaseorchestratorjava.state.StateLockManager;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件系统发布组存储：
 * <ul>
 *   <li>每个发布组一个 JSON：groups/{groupId}.json，先写 tmp 再原子替换；</li>
 *   <li>损坏 JSON 抛 STATE_CORRUPT，绝不静默重建；</li>
 *   <li>组锁与发布锁共用同一套锁表（group:{groupId} 命名空间），
 *       因此组级操作与直接对成员的操作不会互相踩锁。</li>
 * </ul>
 */
@Component
public class FileGroupRepository implements GroupRepository {

    private static final Logger log = LoggerFactory.getLogger(FileGroupRepository.class);

    private final OrchestratorProperties properties;
    private final ObjectMapper mapper;
    private final StateLockManager locks;

    public FileGroupRepository(OrchestratorProperties properties, ObjectMapper mapper, StateLockManager locks) {
        this.properties = properties;
        this.mapper = mapper;
        this.locks = locks;
    }

    private Path groupsDir() {
        return properties.dataPath().resolve("groups");
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
            log.debug("group state saved id={} status={} members={}",
                    group.getGroupId(), group.getStatus(), group.getMembers().size());
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
        return locks.lock(groupLockKey(groupId), timeoutMillis);
    }

    @Override
    public void unlock(String groupId) {
        locks.unlock(groupLockKey(groupId));
    }

    private static String groupLockKey(String groupId) {
        return "group:" + groupId;
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
