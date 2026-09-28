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
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.StageStatus;
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
import java.util.Map;
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
 *       各自落盘，单个成员失败以 FAILED 终态停在自己身上，其余成员照常推进；
 *       推进结论严格以成员真实状态判定：阶段失败的那一轮就返回 ADVANCE/ok=false
 *       （单服务层失败时正常返回 FAILED 发布而非抛异常，必须检查返回终态），
 *       已在终态的成员后续轮次只记 SKIPPED 并保留原始失败原因，撞锁记 RELEASE_BUSY 跳过；</li>
 *   <li>组记录创建后不再变更，组状态由成员状态实时推导，
 *       进程中断重启后不会出现组与成员互相矛盾。</li>
 * </ul>
 */
@Service
public class DefaultReleaseGroupOrchestrator implements ReleaseGroupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultReleaseGroupOrchestrator.class);

    /**
     * 组级推进时，成员处于这些状态视为“本轮无需/不允许动作”：
     * 推进前按成员最新状态预检，直接记 SKIPPED，绝不再次进入单服务推进。
     */
    private static final Map<ReleaseStatus, String> SKIP_ADVANCE_BY_STATUS = Map.of(
            ReleaseStatus.COMPLETED, "RELEASE_ALREADY_COMPLETED",
            ReleaseStatus.FAILED, "RELEASE_FAILED",
            ReleaseStatus.ROLLING_BACK, "RELEASE_ROLLING_BACK",
            ReleaseStatus.ROLLED_BACK, "RELEASE_ROLLED_BACK");

    /**
     * 推进过程中竞态拿到这些码，同样视为本轮未执行（SKIPPED），而不是失败：
     * 另一轮推进正在推进该成员（本轮不再插手），或单服务层的状态守卫拒绝。
     */
    private static final Set<String> SKIP_ADVANCE_CODES = Set.of(
            "RELEASE_ALREADY_COMPLETED", "RELEASE_FAILED",
            "RELEASE_ROLLING_BACK", "RELEASE_ROLLED_BACK", "RELEASE_BUSY");

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
        List<MemberOutcome> outcomes = runPerMember(group, "ADVANCE", this::advanceMember);
        journal.append(groupId, "GROUP_ADVANCE_SWEEP", summarize(outcomes));
        return new GroupOperationResult(groupId, "ADVANCE", outcomes, view(group));
    }

    @Override
    public GroupOperationResult rollbackGroup(String groupId) {
        ReleaseGroup group = loadGroup(groupId);
        log.info("group rollback requested groupId={} members={}", groupId, group.getMembers().size());
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
                return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                        "ROLLBACK", e.getCode(), e.getMessage());
            }
        });
        journal.append(groupId, "GROUP_ROLLBACK_SWEEP", summarize(outcomes));
        return new GroupOperationResult(groupId, "ROLLBACK", outcomes, view(group));
    }

    /**
     * 单个成员一轮推进的结论判定，严格以成员发布的真实状态为准：
     * <ol>
     *   <li>推进前读取最新状态：终态（COMPLETED / FAILED / ROLLING_BACK / ROLLED_BACK）
     *       直接记 SKIPPED，不调用单服务推进，不重复部署；FAILED 时把原始失败阶段、
     *       失败码与原因原样带回结论，调用方无需再查组详情；</li>
     *   <li>残留 RUNNING（进程中断后尚未恢复）也不重跑阶段，记 SKIPPED 并提示先恢复；</li>
     *   <li>其余情况调用单服务推进：单服务层在阶段失败时是“正常返回 FAILED 发布”，
     *       因此必须检查返回值的真实终态——FAILED 即本轮失败结论（带阶段与原因），
     *       绝不能因为没抛异常就记成功；</li>
     *   <li>与并发的另一轮推进撞锁（RELEASE_BUSY）或状态守卫拒绝：本轮未执行，
     *       记 SKIPPED，既不算成功也不覆盖任何既有结论。</li>
     * </ol>
     */
    private MemberOutcome advanceMember(ReleaseGroup.Member member) {
        Release before = releaseRepository.find(member.releaseId())
                .orElseThrow(() -> new ReleaseException("STATE_CORRUPT",
                        "group member release missing: " + member.releaseId()));

        String skipCode = SKIP_ADVANCE_BY_STATUS.get(before.getStatus());
        if (skipCode != null) {
            return MemberOutcome.failure(member.serviceName(), member.releaseId(), "SKIPPED",
                    skipCode, skipReason(before));
        }
        if (findRunningStage(before) != null) {
            // 阶段 RUNNING 已落盘但未走恢复：说明上一轮在执行中被中断，
            // 绝不能再次进入该阶段（否则重复部署/重复执行），等恢复把它调和成终态。
            StageName running = findRunningStage(before);
            return MemberOutcome.failure(member.serviceName(), member.releaseId(), "SKIPPED",
                    "MEMBER_INTERRUPTED_RUNNING",
                    "member stage " + running + " is still RUNNING (process interruption);"
                            + " run recovery before advancing again");
        }

        Release after;
        try {
            after = releaseOrchestrator.advance(member.releaseId());
        } catch (ReleaseException e) {
            if (SKIP_ADVANCE_CODES.contains(e.getCode())) {
                return MemberOutcome.failure(member.serviceName(), member.releaseId(), "SKIPPED",
                        e.getCode(), e.getMessage());
            }
            return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                    "ADVANCE", e.getCode(), e.getMessage());
        }
        if (after.getStatus() == ReleaseStatus.FAILED) {
            // 阶段在本轮执行中失败：单服务层已把它落盘为 FAILED 终态，
            // 这一轮的逐成员结论必须是失败，并带可识别的阶段、码与原因。
            return MemberOutcome.failure(member.serviceName(), member.releaseId(),
                    "ADVANCE", after.getFailureCode(), failureDescription(after));
        }
        return MemberOutcome.success(member.serviceName(), member.releaseId(), "ADVANCE");
    }

    /** 找成员发布上残留的 RUNNING 阶段（中断未恢复标志），没有则 null。 */
    private StageName findRunningStage(Release release) {
        for (StageName name : StageName.ordered()) {
            if (release.getStages().get(name).getStatus() == StageStatus.RUNNING) {
                return name;
            }
        }
        return null;
    }

    /** 跳过一个终态/受阻成员时，给出保留原始失败原因的可读说明。 */
    private String skipReason(Release release) {
        if (release.getStatus() == ReleaseStatus.FAILED) {
            return "member stays in terminal FAILED at stage " + release.getFailureStage()
                    + " (" + release.getFailureCode() + "): " + release.getFailureReason()
                    + "; not re-run, roll back or submit a new release";
        }
        return "member is " + release.getStatus() + "; no advance action taken this round";
    }

    /** 本轮阶段失败时的结论说明：阶段 + 码 + 原因，调用方无需再查详情。 */
    private String failureDescription(Release release) {
        return "stage " + release.getFailureStage() + " failed ("
                + release.getFailureCode() + "): " + release.getFailureReason();
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

    /** 组状态完全由成员状态推导，不持久化，重启后天然一致。 */
    static GroupStatus deriveStatus(List<Release> releases) {
        boolean anyRollingBack = releases.stream().anyMatch(r -> r.getStatus() == ReleaseStatus.ROLLING_BACK);
        if (anyRollingBack) {
            return GroupStatus.ROLLING_BACK;
        }
        boolean allPending = releases.stream().allMatch(r -> r.getStatus() == ReleaseStatus.PENDING);
        if (allPending) {
            return GroupStatus.PENDING;
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
        boolean anyFailed = releases.stream().anyMatch(r -> r.getStatus() == ReleaseStatus.FAILED);
        if (anyFailed) {
            return GroupStatus.FAILED;
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
