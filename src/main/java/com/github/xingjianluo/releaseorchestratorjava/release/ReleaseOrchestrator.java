package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.artifact.Artifact;
import com.github.xingjianluo.releaseorchestratorjava.artifact.ArtifactStore;
import com.github.xingjianluo.releaseorchestratorjava.error.DuplicateReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.error.InvalidVersionException;
import com.github.xingjianluo.releaseorchestratorjava.error.ReleaseNotFoundException;
import com.github.xingjianluo.releaseorchestratorjava.error.ReleaseNotAdvanceableException;
import com.github.xingjianluo.releaseorchestratorjava.health.HealthGate;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStage;
import com.github.xingjianluo.releaseorchestratorjava.model.ServiceState;
import com.github.xingjianluo.releaseorchestratorjava.repo.StateRepository;
import com.github.xingjianluo.releaseorchestratorjava.version.VersionGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布编排核心：在每服务锁内串行执行触发、分阶段推进与回滚，保证并发与重复执行下结论一致。
 */
@Component
public class ReleaseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrchestrator.class);
    private static final Pattern SEMVER_CORE = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    private final VersionGenerator versionGenerator;
    private final ArtifactStore artifactStore;
    private final HealthGate healthGate;
    private final StateRepository repository;

    public ReleaseOrchestrator(VersionGenerator versionGenerator,
                               ArtifactStore artifactStore,
                               HealthGate healthGate,
                               StateRepository repository) {
        this.versionGenerator = versionGenerator;
        this.artifactStore = artifactStore;
        this.healthGate = healthGate;
        this.repository = repository;
    }

    /**
     * 触发发布（提交即确定性构建 BUILD 阶段）。
     * 同一输入（同发布标识）重复提交：以 DUPLICATE_RELEASE 拒绝并返回既有结论；
     * 同一服务有进行中发布、版本重复或版本降级同样拒绝，原因可区分。
     */
    public ReleaseRecord submit(ReleaseRequest request) {
        String version = versionGenerator.resolveVersion(request);
        String releaseId = versionGenerator.releaseId(request);
        log.info("触发发布输入 service={} version={} sourceRef={} failAtStage={} 派生releaseId={}",
                request.getService(), version, request.getSourceRef(), request.getFailAtStage(), releaseId);

        return repository.withLock(request.getService(), () -> {
            ReleaseRecord existing = repository.findRelease(releaseId);
            if (existing != null) {
                throw new DuplicateReleaseException(
                        "发布重复提交被拒绝：相同输入对应发布 " + releaseId + " 已存在，当前状态="
                                + existing.getState() + "（确定性复用，不重复执行）");
            }

            ServiceState serviceState = repository.getServiceState(request.getService());
            if (serviceState.getActiveReleaseId() != null) {
                throw new DuplicateReleaseException(
                        "并发触发被拒绝：服务 " + request.getService() + " 已有进行中的发布 "
                                + serviceState.getActiveReleaseId());
            }
            validateVersionFlow(request.getService(), version);

            String contentKey = versionGenerator.contentKey(request);
            Artifact artifact = artifactStore.build(request.getService(), version, contentKey);

            ReleaseRecord record = ReleaseRecord.start(
                    releaseId, request, version, artifact.getArtifactId(), artifact.getChecksum(), Instant.now());
            repository.saveRelease(record);
            serviceState = repository.getServiceState(request.getService());
            serviceState.markActive(record, Instant.now());
            repository.saveServiceState(serviceState);
            repository.appendHistory(request.getService(), releaseId, "SUBMITTED",
                    "版本 " + version + " 制品 " + artifact.getArtifactId() + " 提交发布");

            return runStage(record, ReleaseStage.BUILD, artifact);
        });
    }

    /**
     * 推进到下一个阶段。已完成阶段不会重复部署；非进行中（失败/回滚中/稳定/挂起）发布拒绝推进。
     */
    public ReleaseRecord advance(String releaseId) {
        ReleaseRecord persisted = requireRelease(releaseId);
        log.info("推进发布 releaseId={} 当前状态={} 已完成阶段={}",
                releaseId, persisted.getState(), persisted.getCompletedStages());
        return repository.withLock(persisted.getService(), () -> {
            ReleaseRecord record = requireRelease(releaseId);
            if (!record.isAdvanceable()) {
                throw new ReleaseNotAdvanceableException(
                        "发布 " + releaseId + " 不可推进：当前状态=" + record.getState()
                                + "（失败/回滚中/已稳定的发布不得被再次推进）");
            }
            ReleaseStage stage = record.nextStage();
            if (stage == null) {
                throw new ReleaseNotAdvanceableException("发布 " + releaseId + " 所有阶段均已完成");
            }
            // 制品加载也必须处于失败保护区内：完整性失败同样要落为 FAILED 终态。
            return runStage(record, stage, null);
        });
    }

    /**
     * 将指定服务恢复到上一个稳定版本。
     * <ul>
     *   <li>有进行中发布：回滚与发布交叉执行被拒绝；</li>
     *   <li>存在失败待回滚发布：确认恢复（线上指针本来就未动），失败发布进入 ROLLED_BACK；</li>
     *   <li>否则针对已上线版本执行 current/previous 交换；没有上一个稳定版本则拒绝；</li>
     *   <li>回滚是确定性且幂等的，重复回滚得到一致结论，变更历史只追加不删除。</li>
     * </ul>
     */
    public ReleaseRecord rollback(String service) {
        log.info("请求回滚 service={}", service);
        return repository.withLock(service, () -> {
            ServiceState state = repository.getServiceState(service);
            if (state.getActiveReleaseId() != null) {
                throw new com.github.xingjianluo.releaseorchestratorjava.error.RollbackConflictException(
                        "回滚与发布交叉执行被拒绝：服务 " + service + " 发布 "
                                + state.getActiveReleaseId() + " 仍在进行中");
            }

            ReleaseRecord failing = state.getPendingRollbackReleaseId() == null
                    ? null
                    : repository.findRelease(state.getPendingRollbackReleaseId());
            String targetReleaseId;
            String targetVersion;
            if (failing != null) {
                if (failing.getState() != com.github.xingjianluo.releaseorchestratorjava.model.ReleaseState.FAILED) {
                    throw new com.github.xingjianluo.releaseorchestratorjava.error.RollbackConflictException(
                            "发布 " + failing.getReleaseId() + " 状态为 " + failing.getState()
                                    + "，不允许重复回滚");
                }
                // 失败发布从未上线，回滚目标是当前仍在线的稳定版本。
                targetReleaseId = state.getCurrentReleaseId();
                targetVersion = state.getCurrentVersion();
            } else {
                if (state.getPreviousReleaseId() == null) {
                    throw new com.github.xingjianluo.releaseorchestratorjava.error.RollbackConflictException(
                            "服务 " + service + " 没有可回滚的上一个稳定版本");
                }
                targetReleaseId = state.getPreviousReleaseId();
                targetVersion = state.getPreviousVersion();
            }

            String failingReleaseId = failing == null ? state.getCurrentReleaseId() : failing.getReleaseId();
            if (failing != null) {
                failing.markRollingBack(targetReleaseId, Instant.now());
                repository.saveRelease(failing);
                repository.appendHistory(service, failingReleaseId, "ROLLBACK_STARTED",
                        "开始确认恢复到稳定版本 " + targetVersion + "（发布 " + targetReleaseId + "）");
            } else {
                repository.appendHistory(service, failingReleaseId, "ROLLBACK_STARTED",
                        "开始回滚已上线版本到 " + targetVersion + "（发布 " + targetReleaseId + "）");
            }

            // 校验回滚目标制品完整性（目标制品损坏则拒绝回滚并给出原因）。
            if (targetReleaseId != null) {
                ReleaseRecord target = repository.findRelease(targetReleaseId);
                if (target != null) {
                    artifactStore.verify(target.getArtifactId(), target.getArtifactChecksum());
                }
            }

            // 关键不变式：先执行回滚动作（状态落盘），再发布回滚完成结论。
            state = repository.getServiceState(service);
            state.applyRollback(Instant.now());
            repository.saveServiceState(state);

            if (failing != null) {
                failing.markRolledBack(Instant.now());
                repository.saveRelease(failing);
            }
            repository.appendHistory(service, failingReleaseId, "ROLLED_BACK",
                    "线上版本已恢复为 " + targetVersion + "（发布 " + targetReleaseId + "）");

            ReleaseRecord result = failing != null ? failing
                    : repository.findRelease(state.getCurrentReleaseId());
            log.info("回滚完成 service={} 当前稳定版本={} 结果状态={}",
                    service, targetVersion, result == null ? null : result.getState());
            return result;
        });
    }

    public ReleaseRecord get(String releaseId) {
        return requireRelease(releaseId);
    }

    // ---- internals ----

    private ReleaseRecord runStage(ReleaseRecord record, ReleaseStage stage, Artifact artifact) {
        try {
            // 制品加载 + 部署前完整性准入（artifact 为 null 时按记录的制品标识重新加载）。
            if (artifact == null) {
                artifact = artifactStore.get(record.getArtifactId());
            }
            artifactStore.verify(artifact.getArtifactId(), record.getArtifactChecksum());
            // 阶段健康准入（确定性故障注入在此失败）。
            healthGate.check(stage, artifact, record.getFailAtStage());

            record.markStageSucceeded(stage, Instant.now());
            repository.saveRelease(record);
            if (stage == ReleaseStage.FULL) {
                repository.updateServiceState(record.getService(), s -> s.markStable(record, Instant.now()));
                repository.appendHistory(record.getService(), record.getReleaseId(), "STABLE",
                        "版本 " + record.getVersion() + " 全阶段通过并上线");
            } else {
                repository.appendHistory(record.getService(), record.getReleaseId(),
                        "STAGE_SUCCEEDED", "阶段 " + stage + " 通过");
            }
            log.info("阶段决策 releaseId={} stage={} 决策=通过 当前状态={} 已完成阶段={}",
                    record.getReleaseId(), stage, record.getState(), record.getCompletedStages());
            return record;
        } catch (com.github.xingjianluo.releaseorchestratorjava.error.ReleaseException e) {
            record.fail(stage, e.getErrorCode(), e.getMessage(), Instant.now());
            repository.saveRelease(record);
            repository.updateServiceState(record.getService(), s -> s.markFailed(record, Instant.now()));
            repository.appendHistory(record.getService(), record.getReleaseId(), "FAILED",
                    "阶段 " + stage + " 失败，原因码 " + e.getErrorCode() + "：" + e.getMessage());
            log.warn("阶段决策 releaseId={} stage={} 决策=失败 原因码={} 原因={} 最终状态={}",
                    record.getReleaseId(), stage, e.getErrorCode(), e.getMessage(), record.getState());
            throw e;
        }
    }

    /** 版本流转校验：禁止重复发布已存在于线上/历史的版本，禁止版本降级。 */
    private void validateVersionFlow(String service, String newVersion) {
        List<ReleaseRecord> prior = repository.findByService(service);
        for (ReleaseRecord r : prior) {
            if (r.getVersion().equals(newVersion)) {
                throw new InvalidVersionException(
                        "非法版本流转：服务 " + service + " 的版本 " + newVersion
                                + " 已在发布 " + r.getReleaseId() + " 中使用，禁止重复版本（状态="
                                + r.getState() + "）");
            }
        }
        ServiceState state = repository.getServiceState(service);
        if (state.getCurrentVersion() != null && compareSemver(newVersion, state.getCurrentVersion()) <= 0) {
            throw new InvalidVersionException(
                    "非法版本流转：版本 " + newVersion + " 不高于当前线上版本 "
                            + state.getCurrentVersion() + "，禁止降级或重复版本");
        }
    }

    private ReleaseRecord requireRelease(String releaseId) {
        ReleaseRecord r = repository.findRelease(releaseId);
        if (r == null) {
            throw new ReleaseNotFoundException("发布不存在: " + releaseId);
        }
        return r;
    }

    /** 语义化版本核心号比较：返回负数/0/正数；预发布后缀按低于正式版本处理。 */
    static int compareSemver(String a, String b) {
        Matcher ma = SEMVER_CORE.matcher(a);
        Matcher mb = SEMVER_CORE.matcher(b);
        if (!ma.find() || !mb.find()) {
            return a.compareTo(b);
        }
        for (int i = 1; i <= 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(ma.group(i)), Integer.parseInt(mb.group(i)));
            if (cmp != 0) {
                return cmp;
            }
        }
        String suffixA = a.substring(ma.end()).startsWith("-") ? a.substring(ma.end()) : "";
        String suffixB = b.substring(mb.end()).startsWith("-") ? b.substring(mb.end()) : "";
        if (suffixA.isEmpty() && suffixB.isEmpty()) {
            return 0;
        }
        if (suffixA.isEmpty()) {
            return 1;
        }
        if (suffixB.isEmpty()) {
            return -1;
        }
        return suffixA.compareTo(suffixB);
    }
}
