package com.github.xingjianluo.releaseorchestratorjava.group.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMember;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberResult;
import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.state.GroupRepository;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseIdentity;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.state.EventJournal;
import com.github.xingjianluo.releaseorchestratorjava.state.ReleaseRepository;
import com.github.xingjianluo.releaseorchestratorjava.version.VersionCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 默认发布组编排器。
 * <p>核心不变量：
 * <ul>
 *   <li>组内每个成员有独立 releaseId（含 serviceName），同 commit/同内容的不同服务
 *       也各自成立，独立状态文件/锁/阶段/部署历史，互不顶掉；</li>
 *   <li>组操作对每个成员单独加发布锁、单独捕获失败：一个成员失败只影响它自己，
 *       其余成员继续推进；</li>
 *   <li>同服务重复/并发触发由成员发布的确定性身份 + 发布锁 + DUPLICATE_SUBMISSION
 *       保证只产生一次部署；</li>
 *   <li>组状态快照始终可由成员 Release 权威状态重新派生，重启后可调和。</li>
 * </ul>
 */
@Service
public class DefaultReleaseGroupOrchestrator implements ReleaseGroupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultReleaseGroupOrchestrator.class);

    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern GROUP_KEY = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");

    /** 单个成员的确定性计划（提交前算出，不落盘）。 */
    private record Planned(int index, GroupMemberRequest req, String contentHash, String releaseId, String version) {
    }

    private final GroupRepository groupRepository;
    private final ReleaseRepository releaseRepository;
    private final ReleaseOrchestrator releaseOrchestrator;
    private final EventJournal journal;
    private final VersionCalculator versionCalculator;
    private final OrchestratorProperties properties;

    public DefaultReleaseGroupOrchestrator(GroupRepository groupRepository,
                                           ReleaseRepository releaseRepository,
                                           ReleaseOrchestrator releaseOrchestrator,
                                           EventJournal journal,
                                           VersionCalculator versionCalculator,
                                           OrchestratorProperties properties,
                                           ObjectMapper mapper) {
        this.groupRepository = groupRepository;
        this.releaseRepository = releaseRepository;
        this.releaseOrchestrator = releaseOrchestrator;
        this.journal = journal;
        this.versionCalculator = versionCalculator;
        this.properties = properties;
    }

    // ---- submit ----------------------------------------------------------

    @Override
    public ReleaseGroup submit(ReleaseGroupRequest request) {
        validateRequest(request);

        List<Planned> planned = new ArrayList<>();
        Map<String, Integer> seenServices = new LinkedHashMap<>();
        for (int i = 0; i < request.members().size(); i++) {
            GroupMemberRequest m = request.members().get(i);
            validateMember(m);
            if (seenServices.putIfAbsent(m.serviceName(), i) != null) {
                throw new ReleaseException("DUPLICATE_SERVICE_IN_GROUP",
                        "service " + m.serviceName()
                                + " appears more than once in the same release group; one group targets each service once");
            }
            String contentHash = versionCalculator.sha256Hex(m.sourceContent());
            String releaseId = ReleaseIdentity.releaseId(m.serviceName(), m.commit(), contentHash);
            String version = versionCalculator.calculateVersion(
                    m.serviceName(), m.commit(), m.sourceContent());
            planned.add(new Planned(i, m, contentHash, releaseId, version));
        }

        String idempotencyKey = effectiveKey(request.idempotencyKey(), planned);
        validateGroupKey(idempotencyKey);
        String groupId = "grp-" + idempotencyKey;

        groupRepository.find(groupId).ifPresent(existing -> {
            throw new ReleaseException("DUPLICATE_SUBMISSION",
                    "release group " + groupId + " already exists with status " + existing.getStatus()
                            + "; identical group submissions are reused, not resubmitted");
        });

        if (!groupRepository.lock(groupId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("RELEASE_BUSY",
                    "release group is being processed by another trigger: " + groupId);
        }
        try {
            groupRepository.find(groupId).ifPresent(existing -> {
                throw new ReleaseException("DUPLICATE_SUBMISSION",
                        "release group " + groupId + " already exists with status " + existing.getStatus()
                                + "; identical group submissions are reused, not resubmitted");
            });

            // 预飞行：按 releaseId 固定顺序一次性持有全部成员的发布锁再判定，
            // 从而与单服务提交/其他组提交互斥，并保证“发现冲突时一个成员都还没创建”（无孤儿发布）。
            // 锁可重入，后续 materializeMember -> releaseOrchestrator.submit 在同线程再次获取直接成功。
            List<Planned> ordered = new ArrayList<>(planned);
            ordered.sort(Comparator.comparing(Planned::releaseId));
            List<String> heldReleaseLocks = new ArrayList<>();
            try {
                for (Planned p : ordered) {
                    if (!releaseRepository.lock(p.releaseId(), properties.getLockTimeoutMillis())) {
                        throw new ReleaseException("RELEASE_BUSY",
                                "service " + p.req().serviceName()
                                        + " is being processed by another trigger; retry the group submission");
                    }
                    heldReleaseLocks.add(p.releaseId());
                }
                for (Planned p : ordered) {
                    rejectIfAlreadyDeployedElsewhere(groupId, p);
                }

                Instant now = Instant.now();
                ReleaseGroup group = new ReleaseGroup();
                group.setGroupId(groupId);
                group.setIdempotencyKey(idempotencyKey);
                group.setStatus(GroupStatus.PENDING);
                group.setCreatedAt(now);
                group.setUpdatedAt(now);

                // 按组内顺序建立成员（锁顺序不影响成员顺序）。
                for (Planned p : planned) {
                    Release release = createMemberUnderLock(groupId, p);
                    GroupMember member = new GroupMember();
                    member.setIndex(p.index());
                    member.setServiceName(p.req().serviceName());
                    member.setCommit(p.req().commit());
                    member.setContentHash(p.contentHash());
                    member.setReleaseId(release.getReleaseId());
                    member.setVersion(release.getVersion());
                    member.setSourceContent(p.req().sourceContent());
                    member.setFaultInjection(p.req().faultInjection());
                    member.setStatus(release.getStatus());
                    group.getMembers().add(member);
                }

                groupRepository.save(group);
                journal.append(groupId, "GROUP_SUBMITTED",
                        "members=" + group.getMembers().size() + " idempotencyKey=" + idempotencyKey);
                log.info("group submit accepted groupId={} members={}", groupId, group.getMembers().size());
                return group;
            } finally {
                for (String releaseId : heldReleaseLocks) {
                    releaseRepository.unlock(releaseId);
                }
            }
        } finally {
            groupRepository.unlock(groupId);
        }
    }

    /** 持成员锁时的冲突预检：同一服务相同输入已在别处存在则拒绝（本组重试由恢复/复用路径处理）。 */
    private void rejectIfAlreadyDeployedElsewhere(String groupId, Planned p) {
        GroupMemberRequest m = p.req();
        Optional<Release> byId = releaseRepository.find(p.releaseId());
        if (byId.isPresent()) {
            Release r = byId.get();
            if (!groupId.equals(r.getGroupId())) {
                throw new ReleaseException("DUPLICATE_SUBMISSION",
                        "service " + m.serviceName() + " at commit " + m.commit()
                                + " with this content already exists as release " + p.releaseId()
                                + (r.getGroupId() == null ? " (standalone)" : " in group " + r.getGroupId())
                                + "; the same service input is deployed exactly once");
            }
            return; // 组重试：成员已属于本组，交给创建步骤复用
        }
        releaseRepository.findByContent(m.serviceName(), m.commit(), p.contentHash()).ifPresent(r -> {
            throw new ReleaseException("DUPLICATE_SUBMISSION",
                    "service " + m.serviceName() + " at commit " + m.commit()
                            + " with this content already exists as release " + r.getReleaseId()
                            + "; the same service input is deployed exactly once");
        });
    }

    /** 预飞行锁已持有时建立成员发布（submit 内部可重入同一把发布锁）。 */
    private Release createMemberUnderLock(String groupId, Planned p) {
        GroupMemberRequest m = p.req();
        Release existing = releaseRepository.find(p.releaseId()).orElse(null);
        if (existing != null && groupId.equals(existing.getGroupId())) {
            log.info("group member reused groupId={} releaseId={}", groupId, p.releaseId());
            return existing;
        }
        Release submitted = releaseOrchestrator.submit(new ReleaseRequest(
                m.serviceName(), m.commit(), m.sourceContent(),
                "group:" + groupId + "#" + p.index(), m.faultInjection()));
        submitted.setGroupId(groupId);
        releaseRepository.save(submitted);
        return submitted;
    }

    // ---- 占位：其余接口后续迭代实现 ----

    // ---- advance ---------------------------------------------------------

    @Override
    public List<GroupMemberResult> advance(String groupId) {
        ReleaseGroup group = lockAndLoad(groupId);
        try {
            List<GroupMember> members = new ArrayList<>(group.getMembers());
            int parallelism = Math.max(1, Math.min(
                    properties.getGroupAdvanceParallelism(), members.size()));

            List<GroupMemberResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(parallelism);
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (GroupMember member : members) {
                    futures.add(pool.submit(() -> results.add(advanceOne(member))));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        // 单个成员任务自身异常已在 advanceOne 收敛，这里仅兜底，不影响其他结果。
                        log.error("group advance member task failed unexpectedly groupId={}", groupId, e);
                    }
                }
            } finally {
                pool.shutdown();
            }

            refreshFromMembers(group);
            journal.append(groupId, "GROUP_ADVANCED",
                    "members=" + members.size() + " groupStatus=" + group.getStatus());
            results.sort(Comparator.comparingInt(GroupMemberResult::index));
            return results;
        } finally {
            groupRepository.unlock(groupId);
        }
    }

    /**
     * 推进单个成员一个阶段。任何失败/拒绝都被收敛进结果（带该成员自己的错误码），
     * 绝不向上抛出，从而实现组内故障隔离。
     */
    private GroupMemberResult advanceOne(GroupMember member) {
        String releaseId = member.getReleaseId();
        try {
            Release before = releaseRepository.find(releaseId)
                    .orElseThrow(() -> new ReleaseException("RELEASE_NOT_FOUND",
                            "member release missing: " + releaseId));
            boolean advanceable = switch (before.getStatus()) {
                case PENDING, IN_PROGRESS -> true;
                default -> false;
            };
            if (!advanceable) {
                // 失败/回滚/已完成成员：跳过，保留其既有终态与原因，其余成员继续。
                return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                        releaseId, before.getStatus(), false, null,
                        "skipped: member is " + before.getStatus());
            }
            Release after = releaseOrchestrator.advance(releaseId);
            boolean advanced = after.getStatus() != before.getStatus()
                    || stageAttemptIncreased(before, after);
            // 单发布 advance 会把阶段失败收敛为 Release(FAILED) 而不是抛异常，
            // 这里把成员终态上的失败码/原因透传出来，保证失败可解释。
            if (after.getStatus() == ReleaseStatus.FAILED) {
                return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                        releaseId, after.getStatus(), advanced,
                        after.getFailureCode(), after.getFailureReason());
            }
            return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                    releaseId, after.getStatus(), advanced, null,
                    "advanced; status=" + after.getStatus());
        } catch (ReleaseException e) {
            log.info("group advance member isolated failure releaseId={} code={}",
                    releaseId, e.getCode());
            ReleaseStatus observed = releaseRepository.find(releaseId)
                    .map(Release::getStatus).orElse(ReleaseStatus.FAILED);
            return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                    releaseId, observed, false, e.getCode(), e.getMessage());
        }
    }

    private boolean stageAttemptIncreased(Release before, Release after) {
        for (var stage : com.github.xingjianluo.releaseorchestratorjava.model.StageName.ordered()) {
            if (after.getStages().get(stage).getAttempts()
                    > before.getStages().get(stage).getAttempts()) {
                return true;
            }
        }
        return false;
    }

    private ReleaseGroup lockAndLoad(String groupId) {
        if (!groupRepository.lock(groupId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("RELEASE_BUSY",
                    "release group is locked by another operation: " + groupId);
        }
        try {
            return loadGroup(groupId);
        } catch (RuntimeException e) {
            groupRepository.unlock(groupId);
            throw e;
        }
    }

    // ---- rollback（按服务，互不牵连） -------------------------------------

    @Override
    public List<GroupMemberResult> rollback(String groupId) {
        ReleaseGroup group = lockAndLoad(groupId);
        try {
            List<GroupMemberResult> results = new ArrayList<>();
            // 逐成员独立回滚：一个服务没有稳定版本等错误只记录在它自己的结果中。
            for (GroupMember member : group.getMembers()) {
                results.add(rollbackOne(member));
            }
            refreshFromMembers(group);
            journal.append(groupId, "GROUP_ROLLBACK_DONE",
                    "members=" + group.getMembers().size() + " groupStatus=" + group.getStatus());
            return results;
        } finally {
            groupRepository.unlock(groupId);
        }
    }

    @Override
    public Release rollbackMember(String groupId, String serviceName) {
        ReleaseGroup group = lockAndLoad(groupId);
        try {
            GroupMember target = null;
            for (GroupMember member : group.getMembers()) {
                if (member.getServiceName().equals(serviceName)) {
                    target = member;
                    break;
                }
            }
            if (target == null) {
                throw new ReleaseException("GROUP_MEMBER_NOT_FOUND",
                        "service " + serviceName + " is not a member of group " + groupId);
            }
            // 直接复用单发布回滚（权威实现），失败以异常抛出（HTTP 层映射）。
            Release rolled = releaseOrchestrator.rollback(target.getReleaseId());
            refreshFromMembers(group);
            return rolled;
        } finally {
            groupRepository.unlock(groupId);
        }
    }

    /**
     * 回滚单个成员并收敛为结果：
     * FAILED ⇒ 回到各自上一个稳定版本；ROLLED_BACK ⇒ 幂等跳过；
     * 其余状态（含 COMPLETED / 推进中）⇒ 不牵连、不回滚。
     */
    private GroupMemberResult rollbackOne(GroupMember member) {
        String releaseId = member.getReleaseId();
        try {
            Release before = releaseRepository.find(releaseId)
                    .orElseThrow(() -> new ReleaseException("RELEASE_NOT_FOUND",
                            "member release missing: " + releaseId));
            if (before.getStatus() == ReleaseStatus.ROLLED_BACK) {
                return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                        releaseId, ReleaseStatus.ROLLED_BACK, false, null,
                        "rollback idempotent: already rolled back to "
                                + before.getRolledBackToVersion());
            }
            if (before.getStatus() != ReleaseStatus.FAILED
                    && before.getStatus() != ReleaseStatus.IN_PROGRESS) {
                return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                        releaseId, before.getStatus(), false, null,
                        "skipped: member is " + before.getStatus());
            }
            Release after = releaseOrchestrator.rollback(releaseId);
            return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                    releaseId, after.getStatus(), true, null,
                    "rolled back from " + after.getRolledBackFromVersion()
                            + " to " + after.getRolledBackToVersion());
        } catch (ReleaseException e) {
            ReleaseStatus observed = releaseRepository.find(releaseId)
                    .map(Release::getStatus).orElse(ReleaseStatus.FAILED);
            log.info("group rollback member isolated failure releaseId={} code={}",
                    releaseId, e.getCode());
            return new GroupMemberResult(member.getIndex(), member.getServiceName(),
                    releaseId, observed, false, e.getCode(), e.getMessage());
        }
    }

    // ---- query -----------------------------------------------------------

    @Override
    public ReleaseGroup get(String groupId) {
        ReleaseGroup group = loadGroup(groupId);
        refreshFromMembers(group);
        return group;
    }

    @Override
    public List<ReleaseGroup> list() {
        List<ReleaseGroup> groups = new ArrayList<>(groupRepository.findAll());
        groups.sort(Comparator.comparing(ReleaseGroup::getCreatedAt));
        for (ReleaseGroup group : groups) {
            refreshFromMembers(group);
        }
        return groups;
    }

    /**
     * 以成员 Release 的权威状态刷新组内成员快照并派生组状态。
     * 成员状态找不到时保持原快照（恢复期间可能短暂出现）。
     */
    private void refreshFromMembers(ReleaseGroup group) {
        int active = 0;
        int completed = 0;
        int failedLike = 0;
        int known = 0;
        boolean anyLeftPending = false;
        for (GroupMember member : group.getMembers()) {
            Optional<Release> r = releaseRepository.find(member.getReleaseId());
            if (r.isEmpty()) {
                // 成员状态缺失：沿用快照计数（recoverInterrupted 负责补齐）。
                continue;
            }
            Release release = r.get();
            member.setStatus(release.getStatus());
            if (release.getVersion() != null) {
                member.setVersion(release.getVersion());
            }
            known++;
            if (release.getStatus() != ReleaseStatus.PENDING) {
                anyLeftPending = true;
            }
            switch (release.getStatus()) {
                case COMPLETED -> completed++;
                case FAILED, ROLLED_BACK -> failedLike++;
                default -> active++;
            }
        }
        GroupStatus derived;
        if (known == group.getMembers().size() && completed == group.getMembers().size()) {
            derived = GroupStatus.COMPLETED;
        } else if (known == group.getMembers().size() && active == 0 && failedLike > 0) {
            derived = GroupStatus.COMPLETED_WITH_FAILURES;
        } else if (failedLike > 0) {
            derived = GroupStatus.DEGRADED;
        } else if (known > 0 && anyLeftPending) {
            derived = GroupStatus.IN_PROGRESS;
        } else {
            derived = GroupStatus.PENDING;
        }
        if (derived != group.getStatus()) {
            group.setStatus(derived);
            group.setUpdatedAt(Instant.now());
            if (derived == GroupStatus.COMPLETED && group.getCompletedAt() == null) {
                group.setCompletedAt(Instant.now());
            }
            groupRepository.save(group);
        }
    }

    private ReleaseGroup loadGroup(String groupId) {
        return groupRepository.find(groupId)
                .orElseThrow(() -> new ReleaseException("GROUP_NOT_FOUND",
                        "no release group found with id " + groupId));
    }

    // ---- recovery --------------------------------------------------------

    /**
     * 组级重启调和。调用方应先执行单发布恢复（把成员残留 RUNNING 收敛为 FAILED），
     * 这里负责：
     * <ol>
     *   <li>补齐“组元数据已落盘、成员 Release 未落盘”这一中断窗口中缺失的成员；</li>
     *   <li>以成员权威状态刷新组快照。</li>
     * </ol>
     * 幂等：再次扫描，成员齐备且状态一致时不写任何内容。
     */
    @Override
    public int recoverInterrupted() {
        int reconciledGroups = 0;
        for (ReleaseGroup group : groupRepository.findAll()) {
            if (!groupRepository.lock(group.getGroupId(), properties.getLockTimeoutMillis())) {
                log.warn("group recovery skipped locked groupId={}", group.getGroupId());
                continue;
            }
            try {
                ReleaseGroup fresh = groupRepository.find(group.getGroupId()).orElse(null);
                if (fresh == null) {
                    continue;
                }
                boolean changed = false;
                for (GroupMember member : fresh.getMembers()) {
                    if (releaseRepository.find(member.getReleaseId()).isEmpty()) {
                        rebuildMissingMember(fresh.getGroupId(), member);
                        changed = true;
                    }
                }
                GroupStatus before = fresh.getStatus();
                refreshFromMembers(fresh);
                if (changed || fresh.getStatus() != before) {
                    reconciledGroups++;
                    journal.append(fresh.getGroupId(), "GROUP_RECOVERED",
                            "reconciled after restart; status=" + fresh.getStatus());
                }
            } finally {
                groupRepository.unlock(group.getGroupId());
            }
        }
        if (reconciledGroups == 0) {
            log.info("group recovery scan: nothing to reconcile");
        } else {
            log.warn("group recovery scan: reconciled {} group(s)", reconciledGroups);
        }
        return reconciledGroups;
    }

    /** 用组成员输入快照确定性地重建缺失的成员发布（身份不变、只此一次）。 */
    private void rebuildMissingMember(String groupId, GroupMember member) {
        if (member.getSourceContent() == null) {
            throw new ReleaseException("STATE_CORRUPT",
                    "group member " + member.getServiceName()
                            + " release is missing and its input snapshot is absent; refusing silent rebuild for group "
                            + groupId);
        }
        ReleaseRequest request = new ReleaseRequest(
                member.getServiceName(), member.getCommit(), member.getSourceContent(),
                "group:" + groupId + "#" + member.getIndex(), member.getFaultInjection());
        Release release;
        try {
            release = releaseOrchestrator.submit(request);
        } catch (ReleaseException duplicate) {
            // 并发恢复等情况下可能已被补出：确认归属本组后复用。
            release = releaseRepository.find(member.getReleaseId())
                    .orElseThrow(() -> duplicate);
            if (!groupId.equals(release.getGroupId())) {
                throw duplicate;
            }
        }
        release.setGroupId(groupId);
        releaseRepository.save(release);
        log.warn("group recovery rebuilt missing member groupId={} releaseId={}",
                groupId, member.getReleaseId());
    }

    // ---- validation / keys ----------------------------------------------

    private void validateRequest(ReleaseGroupRequest request) {
        if (request == null) {
            throw new ReleaseException("INVALID_REQUEST", "request body is required");
        }
        if (request.members() == null || request.members().isEmpty()) {
            throw new ReleaseException("INVALID_GROUP", "release group must contain at least one member");
        }
        if (request.members().size() > 200) {
            throw new ReleaseException("INVALID_GROUP", "release group is limited to 200 members per submission");
        }
    }

    private void validateMember(GroupMemberRequest m) {
        if (m == null) {
            throw new ReleaseException("INVALID_REQUEST", "group member entry is required");
        }
        if (m.serviceName() == null || !SERVICE_NAME.matcher(m.serviceName()).matches()) {
            throw new ReleaseException("INVALID_SERVICE_NAME",
                    "serviceName must match [a-z0-9][a-z0-9-]{0,63}: " + m.serviceName());
        }
        if (m.commit() == null || !COMMIT.matcher(m.commit()).matches()) {
            throw new ReleaseException("INVALID_COMMIT",
                    "commit must be 7-40 lowercase hex characters: " + m.commit());
        }
        if (m.sourceContent() == null) {
            throw new ReleaseException("INVALID_SOURCE_CONTENT", "sourceContent must not be null");
        }
    }

    private void validateGroupKey(String key) {
        if (!GROUP_KEY.matcher(key).matches()) {
            throw new ReleaseException("INVALID_IDEMPOTENCY_KEY",
                    "idempotencyKey must match [a-zA-Z0-9][a-zA-Z0-9._-]{0,127}: " + key);
        }
    }

    /**
     * 未显式提供幂等键时，由“整组有序输入”确定性派生：
     * 相同成员输入集合（按提交顺序）⇒ 相同键 ⇒ 重复提交被识别；
     * 任一成员的 service/commit/content 变化 ⇒ 键变化。
     */
    private String effectiveKey(String explicit, List<Planned> planned) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (Planned p : planned) {
            sb.append(p.req().serviceName()).append('|')
                    .append(p.req().commit()).append('|')
                    .append(p.contentHash()).append(';');
        }
        return "auto-" + sha256Hex(sb.toString()).substring(0, 16);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
