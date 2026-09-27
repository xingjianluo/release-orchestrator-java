package com.github.xingjianluo.releaseorchestratorjava.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.artifact.ArtifactStore;
import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.ArtifactCoordinates;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
import com.github.xingjianluo.releaseorchestratorjava.state.EventJournal;
import com.github.xingjianluo.releaseorchestratorjava.state.ReleaseRepository;
import com.github.xingjianluo.releaseorchestratorjava.version.VersionCalculator;
import com.github.xingjianluo.releaseorchestratorjava.version.VersionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 默认发布编排器。所有状态转移均在“按 releaseId 加锁 → 从磁盘加载最新状态 → 判定 →
 * 原子写回 + 仅追加事件”的临界区内完成，因此并发触发、重复推进、回滚交叉与中断恢复
 * 都得到相同且可解释的结论。
 */
@Service
public class DefaultReleaseOrchestrator implements ReleaseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultReleaseOrchestrator.class);

    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");

    static final String FAULT_RECOVERED = "RECOVERED_AFTER_INTERRUPT";

    private final ReleaseRepository repository;
    private final EventJournal journal;
    private final VersionCalculator versionCalculator;
    private final ArtifactStore artifactStore;
    private final VersionRegistry versionRegistry;
    private final HealthProbe healthProbe;
    private final OrchestratorProperties properties;
    private final ObjectMapper mapper;

    public DefaultReleaseOrchestrator(ReleaseRepository repository,
                                      EventJournal journal,
                                      VersionCalculator versionCalculator,
                                      ArtifactStore artifactStore,
                                      VersionRegistry versionRegistry,
                                      HealthProbe healthProbe,
                                      OrchestratorProperties properties,
                                      ObjectMapper mapper) {
        this.repository = repository;
        this.journal = journal;
        this.versionCalculator = versionCalculator;
        this.artifactStore = artifactStore;
        this.versionRegistry = versionRegistry;
        this.healthProbe = healthProbe;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * 发布身份派生：releaseId 绑定 (serviceName, commit, contentHash) 三元组。
     * 服务名必须参与身份——单仓多服务场景下，不同服务常用同一提交、同一内容，
     * 若身份不含服务名，组内相同输入会互相顶掉、被误判为同一条发布。
     */
    public static String releaseIdFor(String serviceName, String commit, String contentHash) {
        return "rel-" + serviceName + "-" + commit.substring(0, 7) + "-" + contentHash.substring(0, 12);
    }

    @Override
    public Release submit(ReleaseRequest request) {
        validate(request);
        String contentHash = versionCalculator.sha256Hex(request.sourceContent());
        String releaseId = releaseIdFor(request.serviceName(), request.commit(), contentHash);
        log.info("submit input releaseId={} service={} commit={} contentLen={} idempotencyKey={} faultInjection={}",
                releaseId, request.serviceName(), request.commit(),
                request.sourceContent().length(), request.idempotencyKey(), request.faultInjection());

        if (!repository.lock(releaseId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("RELEASE_BUSY", "release is being processed by another trigger: " + releaseId);
        }
        try {
            Optional<Release> duplicate = repository.find(releaseId)
                    .or(() -> repository.findByContent(
                            request.serviceName(), request.commit(), contentHash));
            if (duplicate.isPresent()) {
                Release existing = duplicate.get();
                log.info("submit rejected as duplicate releaseId={} existingId={} existingStatus={}",
                        releaseId, existing.getReleaseId(), existing.getStatus());
                throw new ReleaseException("DUPLICATE_SUBMISSION",
                        "release for service=" + request.serviceName() + ", commit=" + request.commit()
                                + ", contentHash=" + contentHash + " already exists as " + existing.getReleaseId()
                                + " with status " + existing.getStatus()
                                + "; identical inputs are reused, not resubmitted");
            }

            String version = versionCalculator.calculateVersion(
                    request.serviceName(), request.commit(), request.sourceContent());

            Release release = new Release();
            release.setReleaseId(releaseId);
            release.setServiceName(request.serviceName());
            release.setCommit(request.commit());
            release.setContentHash(contentHash);
            release.setIdempotencyKey(request.idempotencyKey());
            release.setFaultInjection(request.faultInjection());
            release.setSourceContent(request.sourceContent());
            release.setVersion(version);
            release.setStatus(ReleaseStatus.PENDING);
            release.setCreatedAt(Instant.now());
            release.setUpdatedAt(release.getCreatedAt());
            repository.save(release);
            journal.append(releaseId, "RELEASE_SUBMITTED",
                    "service=" + request.serviceName() + " version=" + version
                            + " idempotencyKey=" + request.idempotencyKey()
                            + " faultInjection=" + request.faultInjection());
            log.info("submit accepted releaseId={} deterministicVersion={}", releaseId, version);
            return release;
        } finally {
            repository.unlock(releaseId);
        }
    }

    @Override
    public Release advance(String releaseId) {
        log.info("advance requested releaseId={}", releaseId);
        if (!repository.lock(releaseId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("RELEASE_BUSY", "release is locked by another operation: " + releaseId);
        }
        try {
            Release release = load(releaseId);
            guardAdvanceable(release);

            StageName stage = release.nextStage();
            if (stage == null) {
                throw new ReleaseException("RELEASE_ALREADY_COMPLETED",
                        "release " + releaseId + " already completed at version " + release.getVersion());
            }
            StageName expected = expectedNextStage(release);
            if (stage != expected) {
                // 理论不可达：nextStage 已保证按序，保留为状态错乱的显式防线。
                throw new ReleaseException("STAGE_ORDER_VIOLATION",
                        "refusing to advance: next stage is " + stage + " but ordered expectation is " + expected);
            }

            // RUNNING 落盘后再干活：进程被 kill 时，恢复器据此把该阶段调和为失败终态。
            release.setStatus(ReleaseStatus.IN_PROGRESS);
            release.getStages().get(stage).setStatus(StageStatus.RUNNING);
            release.getStages().get(stage).setStartedAt(Instant.now());
            release.setUpdatedAt(Instant.now());
            repository.save(release);
            journal.append(releaseId, "STAGE_STARTED", "stage=" + stage + " version=" + release.getVersion());

            try {
                executeStage(release, stage);
            } catch (ReleaseException failure) {
                markFailed(release, stage, failure.getCode(), failure.getMessage());
                log.info("advance final releaseId={} status=FAILED stage={} code={} reason={}",
                        releaseId, stage, failure.getCode(), failure.getMessage());
                return release;
            }

            completeStage(release, stage);
            log.info("advance final releaseId={} status={} stage={} -> PASSED",
                    releaseId, release.getStatus(), stage);
            return release;
        } finally {
            repository.unlock(releaseId);
        }
    }

    @Override
    public Release rollback(String releaseId) {
        log.info("rollback requested releaseId={}", releaseId);
        if (!repository.lock(releaseId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("RELEASE_BUSY", "release is locked by another operation: " + releaseId);
        }
        try {
            Release release = load(releaseId);

            if (release.getStatus() == ReleaseStatus.ROLLED_BACK) {
                // 重复回滚幂等：返回既有结论，不重复变更部署指针。
                log.info("rollback idempotent releaseId={} alreadyRolledBack to={}",
                        releaseId, release.getRolledBackToVersion());
                return release;
            }
            if (release.getStatus() != ReleaseStatus.FAILED
                    && release.getStatus() != ReleaseStatus.IN_PROGRESS) {
                throw new ReleaseException("ROLLBACK_NOT_ALLOWED",
                        "rollback is only allowed for failed/in-progress releases, but "
                                + releaseId + " is " + release.getStatus());
            }

            String target = versionRegistry.previousStable(
                    release.getServiceName(), release.getVersion());
            if (target == null) {
                throw new ReleaseException("ROLLBACK_NO_STABLE_VERSION",
                        "no previous stable version exists for service " + release.getServiceName());
            }

            release.setStatus(ReleaseStatus.ROLLING_BACK);
            release.setUpdatedAt(Instant.now());
            repository.save(release);
            journal.append(releaseId, "ROLLBACK_STARTED",
                    "from=" + release.getVersion() + " to=" + target);

            Release stable = repository.findAll().stream()
                    .filter(r -> release.getServiceName().equals(r.getServiceName())
                            && target.equals(r.getVersion())
                            && r.getStatus() == ReleaseStatus.COMPLETED)
                    .max(Comparator.comparing(Release::getCompletedAt))
                    .orElseThrow(() -> new ReleaseException("ROLLBACK_TARGET_MISSING",
                            "stable version metadata not found: " + target));

            writeCurrentPointer(release.getServiceName(), stable);

            release.setRolledBackFromVersion(release.getVersion());
            release.setRolledBackToVersion(target);
            release.setStatus(ReleaseStatus.ROLLED_BACK);
            release.setUpdatedAt(Instant.now());
            release.setCompletedAt(Instant.now());
            repository.save(release);
            journal.append(releaseId, "ROLLBACK_COMPLETED",
                    "from=" + release.getRolledBackFromVersion() + " to=" + target);
            log.info("rollback final releaseId={} status=ROLLED_BACK from={} to={}",
                    releaseId, release.getRolledBackFromVersion(), target);
            return release;
        } finally {
            repository.unlock(releaseId);
        }
    }

    @Override
    public Release get(String releaseId) {
        return load(releaseId);
    }

    @Override
    public List<Release> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Release::getCreatedAt))
                .toList();
    }

    @Override
    public List<RecoveryResult> recoverInterrupted() {
        List<RecoveryResult> results = new java.util.ArrayList<>();
        for (Release release : repository.findAll()) {
            StageName running = findRunningStage(release);
            if (running == null && release.getStatus() != ReleaseStatus.ROLLING_BACK) {
                continue;
            }
            if (!repository.lock(release.getReleaseId(), properties.getLockTimeoutMillis())) {
                log.warn("recovery skipped locked releaseId={}", release.getReleaseId());
                continue;
            }
            try {
                Release fresh = repository.find(release.getReleaseId()).orElseThrow();
                StageName stillRunning = findRunningStage(fresh);
                if (stillRunning != null) {
                    String reason = "stage " + stillRunning
                            + " was left RUNNING by a process interruption; reconciled to FAILED on restart";
                    markFailed(fresh, stillRunning, FAULT_RECOVERED, reason);
                    results.add(new RecoveryResult(fresh.getReleaseId(), stillRunning, fresh.getStatus()));
                } else if (fresh.getStatus() == ReleaseStatus.ROLLING_BACK) {
                    fresh.setStatus(ReleaseStatus.FAILED);
                    fresh.setFailureCode(FAULT_RECOVERED);
                    fresh.setFailureReason("rollback was interrupted; release returned to FAILED, retry rollback");
                    fresh.setUpdatedAt(Instant.now());
                    repository.save(fresh);
                    journal.append(fresh.getReleaseId(), "ROLLBACK_INTERRUPTED",
                            "interrupted while ROLLING_BACK; reconciled to FAILED");
                    results.add(new RecoveryResult(fresh.getReleaseId(), null, fresh.getStatus()));
                }
            } finally {
                repository.unlock(release.getReleaseId());
            }
        }
        if (results.isEmpty()) {
            log.info("recovery scan: no interrupted releases");
        } else {
            log.warn("recovery scan: reconciled {} interrupted release(s)", results.size());
        }
        return results;
    }

    private StageName findRunningStage(Release release) {
        if (release.getStatus() != ReleaseStatus.IN_PROGRESS && release.getStatus() != ReleaseStatus.PENDING) {
            return null;
        }
        for (StageName name : StageName.ordered()) {
            if (release.getStages().get(name).getStatus() == StageStatus.RUNNING) {
                return name;
            }
        }
        return null;
    }

    // ---- internals ------------------------------------------------------

    private void validate(ReleaseRequest request) {
        if (request == null) {
            throw new ReleaseException("INVALID_REQUEST", "request body is required");
        }
        if (request.serviceName() == null || request.serviceName().isBlank()) {
            throw new ReleaseException("INVALID_SERVICE_NAME", "serviceName must not be blank");
        }
        if (!SERVICE_NAME.matcher(request.serviceName()).matches()) {
            throw new ReleaseException("INVALID_SERVICE_NAME",
                    "serviceName must match [a-z0-9][a-z0-9-]{0,63}: " + request.serviceName());
        }
        if (request.commit() == null || !COMMIT.matcher(request.commit()).matches()) {
            throw new ReleaseException("INVALID_COMMIT",
                    "commit must be 7-40 lowercase hex characters: " + request.commit());
        }
        if (request.sourceContent() == null) {
            throw new ReleaseException("INVALID_SOURCE_CONTENT", "sourceContent must not be null");
        }
    }

    private Release load(String releaseId) {
        return repository.find(releaseId)
                .orElseThrow(() -> new ReleaseException("RELEASE_NOT_FOUND",
                        "no release found with id " + releaseId));
    }

    private void guardAdvanceable(Release release) {
        switch (release.getStatus()) {
            case COMPLETED -> throw new ReleaseException("RELEASE_ALREADY_COMPLETED",
                    "release " + release.getReleaseId() + " is already completed");
            case FAILED -> throw new ReleaseException("RELEASE_FAILED",
                    "release " + release.getReleaseId()
                            + " is in terminal FAILED state at stage " + release.getFailureStage()
                            + " (" + release.getFailureCode()
                            + "); roll back or submit a new release instead of advancing");
            case ROLLING_BACK -> throw new ReleaseException("RELEASE_ROLLING_BACK",
                    "release " + release.getReleaseId() + " is being rolled back; advancing is forbidden");
            case ROLLED_BACK -> throw new ReleaseException("RELEASE_ROLLED_BACK",
                    "release " + release.getReleaseId() + " has been rolled back to "
                            + release.getRolledBackToVersion() + "; advancing is forbidden");
            case PENDING, IN_PROGRESS -> { /* 允许推进 */ }
        }
    }

    private StageName expectedNextStage(Release release) {
        for (StageName name : StageName.ordered()) {
            StageStatus status = release.getStages().get(name).getStatus();
            if (status != StageStatus.PASSED) {
                if (status == StageStatus.RUNNING && name != StageName.BUILD
                        && release.getArtifactSha256() == null) {
                    throw new ReleaseException("STAGE_ORDER_VIOLATION",
                            "stage " + name + " is RUNNING without a built artifact");
                }
                return name;
            }
        }
        return null;
    }

    private void executeStage(Release release, StageName stage) {
        String faultSpec = faultSpecOf(release);
        if (stage == StageName.BUILD) {
            byte[] bytes = versionCalculator.buildArtifactBytes(
                    release.getServiceName(), release.getCommit(),
                    release.getSourceContent(), release.getVersion());
            ArtifactCoordinates coords = artifactStore.materialize(
                    release.getServiceName(), release.getVersion(), bytes);
            artifactStore.verify(coords);
            if (!healthProbe.passes(release.getServiceName(), release.getVersion(),
                    StageName.BUILD, faultSpec)) {
                throw new ReleaseException("BUILD_HEALTH_CHECK_FAILED",
                        healthProbe.describe(release.getServiceName(), release.getVersion(),
                                StageName.BUILD, faultSpec));
            }
            release.setArtifactUri(coords.uri());
            release.setArtifactSha256(coords.sha256());
            release.setArtifactSize(coords.size());
            journal.append(release.getReleaseId(), "ARTIFACT_BUILT",
                    "version=" + release.getVersion() + " uri=" + coords.uri()
                            + " sha256=" + coords.sha256() + " size=" + coords.size());
            return;
        }

        ArtifactCoordinates coords = new ArtifactCoordinates(
                release.getArtifactUri(), release.getArtifactSha256(), release.getArtifactSize());
        artifactStore.verify(coords); // 每个阶段准入前完整性校验
        if (!healthProbe.passes(release.getServiceName(), release.getVersion(), stage, faultSpec)) {
            throw new ReleaseException(stage.name() + "_HEALTH_CHECK_FAILED",
                    healthProbe.describe(release.getServiceName(), release.getVersion(),
                            stage, faultSpecOf(release)));
        }
        journal.append(release.getReleaseId(), "STAGE_DEPLOYED",
                "stage=" + stage + " version=" + release.getVersion() + " sha256=" + coords.sha256());
    }

    private void completeStage(Release release, StageName stage) {
        var state = release.getStages().get(stage);
        state.setStatus(StageStatus.PASSED);
        state.setAttempts(state.getAttempts() + 1);
        state.setFinishedAt(Instant.now());
        state.setFailureCode(null);
        state.setFailureReason(null);
        release.setUpdatedAt(Instant.now());

        if (stage == StageName.PRODUCTION) {
            release.setStatus(ReleaseStatus.COMPLETED);
            release.setCompletedAt(Instant.now());
            writeCurrentPointer(release.getServiceName(), release);
            versionRegistry.registerStable(release.getServiceName(), release.getVersion());
            repository.save(release);
            journal.append(release.getReleaseId(), "STAGE_PASSED", "stage=" + stage);
            journal.append(release.getReleaseId(), "RELEASE_COMPLETED",
                    "version=" + release.getVersion() + " sha256=" + release.getArtifactSha256());
        } else {
            release.setStatus(ReleaseStatus.IN_PROGRESS);
            repository.save(release);
            journal.append(release.getReleaseId(), "STAGE_PASSED", "stage=" + stage);
        }
    }

    private void markFailed(Release release, StageName stage, String code, String reason) {
        var state = release.getStages().get(stage);
        state.setStatus(StageStatus.FAILED);
        state.setAttempts(state.getAttempts() + 1);
        state.setFinishedAt(Instant.now());
        state.setFailureCode(code);
        state.setFailureReason(reason);
        release.setStatus(ReleaseStatus.FAILED);
        release.setFailureStage(stage);
        release.setFailureCode(code);
        release.setFailureReason(reason);
        release.setUpdatedAt(Instant.now());
        repository.save(release);
        journal.append(release.getReleaseId(), "STAGE_FAILED",
                "stage=" + stage + " code=" + code + " reason=" + reason);
        journal.append(release.getReleaseId(), "RELEASE_FAILED",
                "stage=" + stage + " code=" + code);
    }

    /** 故障注入标记随发布持久化，保证重启后失败结论仍可复现。 */
    private String faultSpecOf(Release release) {
        return release.getFaultInjection() == null ? "" : release.getFaultInjection();
    }

    private void writeCurrentPointer(String serviceName, Release release) {
        try {
            Path dir = properties.dataPath().resolve("deployments").resolve(serviceName);
            Files.createDirectories(dir);
            Pointer pointer = new Pointer(release.getVersion(), release.getArtifactUri(),
                    release.getArtifactSha256(), Instant.now().toString());
            Path target = dir.resolve("current.json");
            Path tmp = dir.resolve("current.json.tmp-" + ProcessHandle.current().pid());
            Files.write(tmp, mapper.writeValueAsBytes(pointer));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("deployment pointer updated service={} version={} sha256={}",
                    serviceName, release.getVersion(), release.getArtifactSha256());
        } catch (IOException e) {
            throw new ReleaseException("DEPLOYMENT_POINTER_IO_ERROR",
                    "failed to update current deployment pointer for " + serviceName + ": " + e.getMessage(), e);
        }
    }

    private record Pointer(String version, String artifactUri, String artifactSha256, String updatedAt) {
    }
}
