package com.github.xingjianluo.releaseorchestratorjava.state;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseEvent;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于单行 JSON 的仅追加事件日志：
 * <ul>
 *   <li>每次 append 持有进程内 synchronized + JVM 文件锁，杜绝交叉执行乱序；</li>
 *   <li>seq 按日志现有行数递增（单一日志文件，全局有序）；</li>
 *   <li>解析时跳过末尾可能因进程被 kill 而截断的半行，历史不丢失、不错乱。</li>
 * </ul>
 */
@Component
public class FileEventJournal implements EventJournal {

    private static final Logger log = LoggerFactory.getLogger(FileEventJournal.class);

    private final OrchestratorProperties properties;
    private final ObjectMapper mapper;

    public FileEventJournal(OrchestratorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    private Path journalFile() {
        return properties.dataPath().resolve("journal").resolve("events.log");
    }

    private Path lockFile() {
        return properties.dataPath().resolve("journal").resolve("events.lock");
    }

    @Override
    public ReleaseEvent append(String releaseId, String type, String detail) {
        try {
            Files.createDirectories(journalFile().getParent());
            synchronized (this) {
                try (FileChannel channel = FileChannel.open(lockFile(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    long seq = countLines();
                    ReleaseEvent event = new ReleaseEvent(seq + 1, Instant.now(), releaseId, type, detail);
                    String line = mapper.writeValueAsString(event) + System.lineSeparator();
                    Files.writeString(journalFile(), line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    log.info("event seq={} release={} type={} detail={}", seq + 1, releaseId, type, detail);
                    return event;
                }
            }
        } catch (IOException e) {
            throw new ReleaseException("JOURNAL_IO_ERROR",
                    "failed to append event " + type + " for " + releaseId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<ReleaseEvent> readAll() {
        if (!Files.exists(journalFile())) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(journalFile(), StandardCharsets.UTF_8);
            List<ReleaseEvent> events = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(mapper.readValue(line, ReleaseEvent.class));
                } catch (IOException bad) {
                    // 末尾截断的半行：停止解析后续，保留此前全部历史。
                    log.warn("skipping unparseable (possibly truncated) journal line: {}", line);
                    break;
                }
            }
            return events;
        } catch (IOException | UncheckedIOException e) {
            throw new ReleaseException("JOURNAL_IO_ERROR",
                    "failed to read event journal: " + e.getMessage(), e);
        }
    }

    private long countLines() throws IOException {
        if (!Files.exists(journalFile())) {
            return 0;
        }
        long count = 0;
        for (String line : Files.readAllLines(journalFile(), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                count++;
            }
        }
        return count;
    }
}
