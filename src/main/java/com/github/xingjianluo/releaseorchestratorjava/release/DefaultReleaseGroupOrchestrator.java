package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.releaseorchestratorjava.model.GroupOperationResult;
import com.github.xingjianluo.releaseorchestratorjava.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.MemberOutcome;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupView;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.state.EventJournal;
import com.github.xingjianluo.releaseorchestratorjava.state.ReleaseGroupRepository;
import com.github.xingjianluo.releaseorchestratorjava.state.ReleaseRepository;
import com.github.xingjianluo.releaseorchestratorjava.version.VersionCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 默认发布组编排器。
 * <ul>
 *   <li>成员发布身份 = (serviceName, commit, contentHash)，与单服务提交完全同源，
 *       因此组内/组外相同输入都收敛到同一条发布，绝不重复部署；</li>
 *   <li>组身份 = 全部成员身份的确定性摘要：相同成员集合永远得到同一个 groupId，
 *       重复或并发提交同一组幂等返回既有组；</li>
 *   <li>组级推进/回滚只是“逐成员调用单服务编排 + 逐成员记录结论”，成员各自持锁、
 *       各自落盘，单个成员失败以 FAILED 终态停在自己身上，其余成员照常推进；</li>
 *   <li>组记录创建后不再变更，组状态由成员状态实时推导，
 *       进程中断重启后不会出现组与成员互相矛盾。</li>
 * </ul>
 */
@Service
public class DefaultReleaseGroupOrchestrator implements ReleaseGroupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultReleaseGroupOrchestrator.class);

    /**
     * 组级推进时，成员处于这些（终态/禁止推进）状态视为“本轮无需动作”，
     * 记 SKIPPED 并携带原有原因，而不是重新执行、更不是失败或成功。
     */
    private static final Set<String> SKIP_ADVANCE_CODES = Set.of(
            "RELEASE_ALREADY_COMPLETED", "RELEASE_FAILED",
            "RELEASE_ROLLING_BACK", "RELEASE_ROLLED_BACK");

    /**
     * 成员锁正被另一个触发方持有（并发推进同一成员）时，本轮不执行该成员，
     * 记 SKIPPED/BUSY；持锁方负责给出真实结论，绝不允许把忙等误报为失败或成功。
     */
    private static final String MEMBER_BUSY_CODE = "MEMBER_BUSY";

    private final ReleaseOrchestrator releaseOrchestrator;
    private final ReleaseRepository releaseRepository;
    private final ReleaseGroupRepository groupRepository;
    private final VersionCalculator versionCalculator;
    private final EventJournal journal;
    private final OrchestratorProperties properties;

    public DefaultReleaseGroupOrchestrator(ReleaseOrchestrator releaseOrchestrator,
                                           ReleaseRepository releaseRepository,
                                           ReleaseGroupRepository groupRepository,
                                           VersionCalculator versionCalculator,
                                           EventJournal journal,
                                           OrchestratorProperties properties) {
        this.releaseOrchestrator = releaseOrchestrator;
        this.releaseRepository = releaseRepository;
        this.groupRepository = groupRepository;
        this.versionCalculator = versionCalculator;
        this.journal = journal;
        this.properties = properties;
    }

    // ---- submit ---------------------------------------------------------

    @Override
    public ReleaseGroupView submitGroup(ReleaseGroupRequest request) {
        List<ReleaseRequest> members = validate(request);
        String groupId = groupIdFor(members);
        log.info("group submit input groupId={} groupKey={} members={}",
                groupId, request.groupKey(), members.size());

        if (!groupRepository.lock(groupId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("GROUP_BUSY", "group is being submitted concurrently: " + groupId);
        }
        try {
            var existing = groupRepository.find(groupId);
            if (existing.isPresent()) {
                // 幂等：相同成员集合重复/并发提交，返回既有组，不产生任何新发布。
                log.info("group submit idempotent hit groupId={}", groupId);
                return view(existing.get());
            }

            ReleaseGroup group = new ReleaseGroup();
            group.setGroupId(groupId);
            group.setGroupKey(request.groupKey());
            group.setCreatedAt(Instant.now());
            for (ReleaseRequest member : members) {
                group.getMembers().add(new ReleaseGroup.Member(
                        member.serviceName(), submitOrAdopt(member)));
            }
            groupRepository.save(group);
            journal.append(groupId, "GROUP_SUBMITTED",
                    "groupKey=" + request.groupKey() + " members=" + group.getMembers().size()
                            + " services=" + group.getMembers().stream()
                            .map(ReleaseGroup.Member::serviceName).sorted().toList());
            log.info("group submit accepted groupId={} members={}", groupId, group.getMembers().size());
            return view(group);
        } finally {
            groupRepository.unlock(groupId);
        }
    }

    /**
     * 提交成员发布；若同一 (service, commit, content) 的发布已存在
     * （组内重试、或此前单服务提交过），直接收养其身份——
     * 相同输入本来就该是同一条发布，不允许产生第二次部署。
     */
    private String submitOrAdopt(ReleaseRequest member) {
        try {
            return releaseOrchestrator.submit(member).getReleaseId();
        } catch (ReleaseException e) {
            if (!"DUPLICATE_SUBMISSION".equals(e.getCode())) {
                throw e;
            }
            String contentHash = versionCalculator.sha256Hex(member.sourceContent());
            String releaseId = DefaultReleaseOrchestrator.releaseIdFor(
                    member.serviceName(), member.commit(), contentHash);
            Release existing = releaseRepository.find(releaseId)
                    .filter(r -> member.serviceName().equals(r.getServiceName()))
                    .orElseThrow(() -> e);
            log.info("group member adopted existing release service={} releaseId={} status={}",
                    member.serviceName(), releaseId, existing.getStatus());
            return releaseId;
        }
    }

    // ---- advance / rollback ----------------------------------------------

    @Override
    public GroupOperationResult advanceGroup(String groupId) {
        ReleaseGroup group = loadGroup(groupId);
        log.info("group advance requested groupId={} members={}", groupId, group.getMembers().size());
        // 组锁串行化同一组的并发扫描：任一轮推进的临界区（逐成员执行 + 汇总 + 事件）
        // 互不交叠，并发触发只会排队得到“真实执行”或“跳过”的稳定结论，
        // 不会出现两条扫描同时写同一成员、结论互相覆盖。
        if (!groupRepository.lock(groupId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("GROUP_BUSY", "group is being advanced concurrently: " + groupId);
        }
        try {
            List<MemberOutcome> outcomes = runPerMember(group, "ADVANCE", this::advanceMember);
            journal.append(groupId, "GROUP_ADVANCE_SWEEP", summarize(outcomes));
            return new GroupOperationResult(groupId, "ADVANCE", outcomes, view(group));
        } finally {
            groupRepository.unlock(groupId);
        }
    }

    /**
     * 单个成员推进一轮的结论判定。语义必须与成员真实结果严格一致：
     * <ul>
     *   <li>阶段真正执行并通过 → SUCCESS；</li>
     *   <li>阶段执行失败（单服务编排把成员置为 FAILED 终态后正常返回）
     *       → 本轮必须明确返回 FAILED 结论并带上失败阶段/错误码/原因，
     *       不能因为没有抛异常就误记成功；</li>
     *   <li>成员已在失败/回滚/完成等终态 → SKIPPED，保留既有原因，不重跑；</li>
     *   <li>成员锁被并发触发方持有 → SKIPPED/MEMBER_BUSY，由持锁方给真实结论。</li>
     * </ul>
     */
    private MemberOutcome advanceMember(ReleaseGroup.Member member) {
        Release before = releaseRepository.find(member.releaseId()).orElse(null);
        Release result;
        try {
            result = releaseOrchestrator.advance(member.releaseId());
        } catch (ReleaseException e) {
            if (SKIP_ADVANCE_CODES.contains(e.getCode())) {
                return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                        "SKIPPED", e.getCode(), e.getMessage());
            }
            if ("RELEASE_BUSY".equals(e.getCode())) {
                return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                        "SKIPPED", MEMBER_BUSY_CODE,
                        "member is being advanced by another trigger this round: " + e.getMessage());
            }
            return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                    "ADVANCE", e.getCode(), e.getMessage());
        }
        if (result.getStatus() == ReleaseStatus.FAILED) {
            // 关键：advance() 阶段失败时是“落 FAILED 终态后正常返回”而不是抛异常，
            // 组级结论必须以成员落盘状态为准，否则会把失败谎报为成功。
            return MemberOutcome.failure(member.serviceName(), member.releaseId(), "ADVANCE",
                    result.getFailureCode(), describeFailure(result));
        }
        if (before != null && before.getStatus() == ReleaseStatus.FAILED
                && result.getStatus() != ReleaseStatus.FAILED) {
            // 状态防线：失败终态绝不可能因一次推进被翻成成功。理论不可达。
            throw new ReleaseException("STATE_CORRUPT",
                    "member " + member.releaseId() + " escaped terminal FAILED via advance");
        }
        return MemberOutcome.success(member.serviceName(), member.releaseId(), "ADVANCE");
    }

    /** 失败说明要让调用方无需再查组详情即可定位：阶段 + 错误码 + 原始原因。 */
    private String describeFailure(Release release) {
        return "stage " + release.getFailureStage() + " failed with "
                + release.getFailureCode() + ": " + release.getFailureReason();
    }

    @Override
    public GroupOperationResult rollbackGroup(String groupId) {
        ReleaseGroup group = loadGroup(groupId);
        log.info("group rollback requested groupId={} members={}", groupId, group.getMembers().size());
        // 与推进共用同一把组锁：回滚扫描与推进扫描互不交叠，避免成员结论串台。
        if (!groupRepository.lock(groupId, properties.getLockTimeoutMillis())) {
            throw new ReleaseException("GROUP_BUSY", "group is being operated on concurrently: " + groupId);
        }
        try {
            List<MemberOutcome> outcomes = runPerMember(group, "ROLLBACK", member -> {
                Release release = releaseRepository.find(member.releaseId())
                        .orElseThrow(() -> new ReleaseException("STATE_CORRUPT",
                                "group member release missing: " + member.releaseId()));
                // 只有失败/推进中/已回滚（幂等）的成员需要动作；其余明确跳过，互不牵连。
                if (release.getStatus() != ReleaseStatus.FAILED
                        && release.getStatus() != ReleaseStatus.IN_PROGRESS
                        && release.getStatus() != ReleaseStatus.ROLLED_BACK) {
                    return MemberOutcome.failure(member.serviceName(), member.releaseId(), "SKIPPED",
                            "ROLLBACK_NOT_ALLOWED",
                            "member is " + release.getStatus() + "; only failed/in-progress members roll back");
                }
                try {
                    releaseOrchestrator.rollback(member.releaseId());
                    return MemberOutcome.success(member.serviceName(), member.releaseId(), "ROLLBACK");
                } catch (ReleaseException e) {
                    if ("RELEASE_BUSY".equals(e.getCode())) {
                        return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                                "SKIPPED", MEMBER_BUSY_CODE,
                                "member is being operated on by another trigger this round: " + e.getMessage());
                    }
                    return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                            "ROLLBACK", e.getCode(), e.getMessage());
                }
            });
            journal.append(groupId, "GROUP_ROLLBACK_SWEEP", summarize(outcomes));
            return new GroupOperationResult(groupId, "ROLLBACK", outcomes, view(group));
        } finally {
            groupRepository.unlock(groupId);
        }
    }

    /**
     * 逐成员并行执行（并行度受 groupParallelism 约束）。
     * 成员之间只共享“各自独立”的单服务编排，锁粒度是 releaseId，
     * 因此不同服务互不阻塞，整体耗时随成员数近似线性。
     */
    private List<MemberOutcome> runPerMember(ReleaseGroup group, String operation,
                                             MemberAction action) {
        List<ReleaseGroup.Member> members = group.getMembers();
        int parallelism = Math.max(1, Math.min(properties.getGroupParallelism(), members.size()));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<MemberOutcome>> futures = new ArrayList<>(members.size());
            for (ReleaseGroup.Member member : members) {
                futures.add(pool.submit(() -> action.run(member)));
            }
            List<MemberOutcome> outcomes = new ArrayList<>(members.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    outcomes.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ReleaseException("GROUP_OPERATION_INTERRUPTED",
                            "interrupted while waiting for group " + operation, e);
                } catch (ExecutionException e) {
                    // 兜底：动作本身已吞掉 ReleaseException，走到这里属于编程错误。
                    ReleaseGroup.Member member = members.get(i);
                    outcomes.add(MemberOutcome.failure(member.serviceName(), member.releaseId(),
                            operation, "GROUP_MEMBER_UNEXPECTED_ERROR", String.valueOf(e.getCause())));
                }
            }
            return outcomes;
        } finally {
            pool.shutdown();
        }
    }

    private String summarize(List<MemberOutcome> outcomes) {
        long ok = outcomes.stream().filter(MemberOutcome::ok).count();
        long skipped = outcomes.stream().filter(o -> "SKIPPED".equals(o.action())).count();
        long failed = outcomes.size() - ok - skipped;
        return "total=" + outcomes.size() + " ok=" + ok + " skipped=" + skipped + " failed=" + failed;
    }

    // ---- queries ----------------------------------------------------------

    @Override
    public ReleaseGroupView getGroup(String groupId) {
        return view(loadGroup(groupId));
    }

    @Override
    public List<ReleaseGroupView> listGroups() {
        return groupRepository.findAll().stream()
                .sorted(Comparator.comparing(ReleaseGroup::getCreatedAt))
                .map(this::view)
                .toList();
    }

    // ---- internals --------------------------------------------------------

    private ReleaseGroup loadGroup(String groupId) {
        return groupRepository.find(groupId)
                .orElseThrow(() -> new ReleaseException("GROUP_NOT_FOUND",
                        "no release group found with id " + groupId));
    }

    private ReleaseGroupView view(ReleaseGroup group) {
        List<Release> releases = new ArrayList<>(group.getMembers().size());
        for (ReleaseGroup.Member member : group.getMembers()) {
            releases.add(releaseRepository.find(member.releaseId())
                    .orElseThrow(() -> new ReleaseException("STATE_CORRUPT",
                            "group " + group.getGroupId() + " references missing release "
                                    + member.releaseId())));
        }
        return new ReleaseGroupView(group.getGroupId(), group.getGroupKey(),
                deriveStatus(releases), releases);
    }

    /**
     * 组状态完全由成员状态推导，不持久化，重启后天然一致。
     * 失败终态优先：只要有成员 FAILED，组就是 FAILED——即使其他成员仍在推进中，
     * 这样“某轮有成员失败”时组状态与该轮逐成员结论保持一致，不会被活跃成员掩盖。
     */
    static GroupStatus deriveStatus(List<Release> releases) {
        boolean anyRollingBack = releases.stream().anyMatch(r -> r.getStatus() == ReleaseStatus.ROLLING_BACK);
        if (anyRollingBack) {
            return GroupStatus.ROLLING_BACK;
        }
        boolean allPending = releases.stream().allMatch(r -> r.getStatus() == ReleaseStatus.PENDING);
        if (allPending) {
            return GroupStatus.PENDING;
        }
        boolean anyFailed = releases.stream().anyMatch(r -> r.getStatus() == ReleaseStatus.FAILED);
        if (anyFailed) {
            return GroupStatus.FAILED;
        }
        boolean anyActive = releases.stream().anyMatch(r ->
                r.getStatus() == ReleaseStatus.PENDING || r.getStatus() == ReleaseStatus.IN_PROGRESS);
        if (anyActive) {
            return GroupStatus.IN_PROGRESS;
        }
        boolean allCompleted = releases.stream().allMatch(r -> r.getStatus() == ReleaseStatus.COMPLETED);
        if (allCompleted) {
            return GroupStatus.COMPLETED;
        }
        boolean allRolledBack = releases.stream().allMatch(r -> r.getStatus() == ReleaseStatus.ROLLED_BACK);
        if (allRolledBack) {
            return GroupStatus.ROLLED_BACK;
        }
        return GroupStatus.PARTIAL;
    }

    /** 组身份 = 排序后成员发布身份列表的摘要，与成员顺序、groupKey 无关。 */
    private String groupIdFor(List<ReleaseRequest> members) {
        StringBuilder identities = new StringBuilder();
        members.stream()
                .map(m -> DefaultReleaseOrchestrator.releaseIdFor(m.serviceName(), m.commit(),
                        versionCalculator.sha256Hex(m.sourceContent())))
                .sorted()
                .forEach(id -> identities.append(id).append('\n'));
        return "grp-" + versionCalculator.sha256Hex(identities.toString()).substring(0, 16);
    }

    private List<ReleaseRequest> validate(ReleaseGroupRequest request) {
        if (request == null || request.members() == null || request.members().isEmpty()) {
            throw new ReleaseException("INVALID_GROUP_REQUEST", "group must contain at least one member");
        }
        Set<String> services = new HashSet<>();
        for (ReleaseRequest member : request.members()) {
            if (member == null || member.serviceName() == null || member.serviceName().isBlank()) {
                throw new ReleaseException("INVALID_GROUP_REQUEST", "every member needs a serviceName");
            }
            if (member.commit() == null || member.commit().length() < 7) {
                throw new ReleaseException("INVALID_COMMIT",
                        "member commit must be at least 7 characters: " + member.serviceName());
            }
            if (member.sourceContent() == null) {
                throw new ReleaseException("INVALID_SOURCE_CONTENT",
                        "member sourceContent must not be null: " + member.serviceName());
            }
            if (!services.add(member.serviceName())) {
                // 一个服务在组内只能出现一次，否则同一服务会被编排两次，违背故障隔离语义。
                throw new ReleaseException("GROUP_DUPLICATE_SERVICE",
                        "service appears more than once in group: " + member.serviceName());
            }
        }
        // 按服务名排序，保证组身份与成员处理顺序确定性，与调用方传入顺序无关。
        return request.members().stream()
                .sorted(Comparator.comparing(ReleaseRequest::serviceName))
                .toList();
    }

    private interface MemberAction {
        MemberOutcome run(ReleaseGroup.Member member);
    }
}
