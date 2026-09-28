package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.GroupOperationResult;
import com.github.xingjianluo.releaseorchestratorjava.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.MemberOutcome;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupView;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseGroupOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 组内故障隔离：单个服务失败只停在自己身上；按服务回滚互不牵连。 */
class ReleaseGroupFailureIsolationTest extends AbstractOrchestratorTest {

    private static final String COMMIT = "1111111789abcdef0123456789abcdef01234567";

    @Autowired
    private ReleaseGroupOrchestrator groups;

    private ReleaseRequest member(String service, String commit, String content, String fault) {
        return new ReleaseRequest(service, commit, content, null, fault);
    }

    private Release memberOf(ReleaseGroupView view, String service) {
        return view.releases().stream()
                .filter(r -> r.getServiceName().equals(service)).findFirst().orElseThrow();
    }

    @Test
    void failingMemberStopsAloneAndRollsBackIndependently() throws Exception {
        // 前置：svc-bad 先有一个稳定版本 v1（回滚目标）。
        Release v1 = orchestrator.submit(member("svc-bad", "0000000789abcdef0123456789abcdef01234567",
                "bad-service-v1", null));
        for (int i = 0; i < 4; i++) {
            orchestrator.advance(v1.getReleaseId());
        }
        assertEquals(ReleaseStatus.COMPLETED, orchestrator.get(v1.getReleaseId()).getStatus());

        // 组：两个健康服务 + svc-bad 的新版本（CANARY 阶段必然失败）。
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-iso", List.of(
                member("svc-ok-1", COMMIT, "ok-one", null),
                member("svc-ok-2", COMMIT, "ok-two", null),
                member("svc-bad", COMMIT, "bad-service-v2", "fail:CANARY"))));
        String badId = memberOf(group, "svc-bad").getReleaseId();

        // 4 轮组级推进：失败成员在 CANARY 停下后，后续轮次对它只记 SKIPPED。
        GroupOperationResult last = null;
        for (int i = 0; i < 4; i++) {
            last = groups.advanceGroup(group.groupId());
        }
        ReleaseGroupView view = last.group();
        testLog.info("after sweeps: groupStatus={} outcomes={}", view.status(), last.outcomes());

        // 失败成员：可解释终态，失败阶段与原因保留。
        Release bad = memberOf(view, "svc-bad");
        assertEquals(ReleaseStatus.FAILED, bad.getStatus());
        assertEquals(StageName.CANARY, bad.getFailureStage());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", bad.getFailureCode());
        assertNotNull(bad.getFailureReason());
        // 其余成员不受牵连，各自走完全部阶段。
        assertEquals(ReleaseStatus.COMPLETED, memberOf(view, "svc-ok-1").getStatus());
        assertEquals(ReleaseStatus.COMPLETED, memberOf(view, "svc-ok-2").getStatus());
        assertEquals(GroupStatus.FAILED, view.status());
        // 失败成员的历史完整可查。
        assertTrue(journal.readAll().stream().anyMatch(e ->
                e.releaseId().equals(badId) && "RELEASE_FAILED".equals(e.type())));

        // 按服务回滚：只有失败成员回到自己的上一个稳定版本。
        GroupOperationResult rollback = groups.rollbackGroup(group.groupId());
        MemberOutcome badOutcome = rollback.outcomes().stream()
                .filter(o -> o.serviceName().equals("svc-bad")).findFirst().orElseThrow();
        assertTrue(badOutcome.ok(), "failed member rolls back: " + badOutcome);
        assertEquals("ROLLBACK", badOutcome.action());
        for (MemberOutcome o : rollback.outcomes()) {
            if (!o.serviceName().equals("svc-bad")) {
                assertEquals("SKIPPED", o.action(), "healthy members untouched: " + o);
                assertEquals("ROLLBACK_NOT_ALLOWED", o.code());
            }
        }

        ReleaseGroupView after = rollback.group();
        Release badAfter = memberOf(after, "svc-bad");
        assertEquals(ReleaseStatus.ROLLED_BACK, badAfter.getStatus());
        assertEquals(v1.getVersion(), badAfter.getRolledBackToVersion(),
                "rolls back to its own previous stable, not anyone else's");
        assertEquals(ReleaseStatus.COMPLETED, memberOf(after, "svc-ok-1").getStatus());
        assertEquals(ReleaseStatus.COMPLETED, memberOf(after, "svc-ok-2").getStatus());
        assertEquals(GroupStatus.PARTIAL, after.status(),
                "mixed terminal states (rolled back + completed) derive PARTIAL");

        // 部署指针：svc-bad 指回 v1，健康成员仍指向各自新版本。
        String badPointer = Files.readString(
                dataDir().resolve("deployments/svc-bad/current.json"));
        assertTrue(badPointer.contains(v1.getVersion()), "svc-bad pointer back to v1");
        String okPointer = Files.readString(
                dataDir().resolve("deployments/svc-ok-1/current.json"));
        assertTrue(okPointer.contains(memberOf(after, "svc-ok-1").getVersion()),
                "healthy member pointer untouched");
        testLog.info("final state: svc-bad ROLLED_BACK to {}, others COMPLETED, group={}",
                badAfter.getRolledBackToVersion(), after.status());
    }

    @Test
    void failingMemberIsReportedFailedInTheSweepItFailsAndSkippedAfterwards() {
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-same-round", List.of(
                member("svc-good-1", COMMIT, "same-round-one", null),
                member("svc-good-2", COMMIT, "same-round-two", null),
                member("svc-loser", COMMIT, "same-round-loser", "fail:CANARY"))));

        MemberOutcome loser;
        MemberOutcome good1;

        // 第 1 轮 BUILD：三个成员都真正执行并成功，结论各自为 ADVANCE/SUCCESS。
        GroupOperationResult build = groups.advanceGroup(group.groupId());
        loser = outcomeOf(build, "svc-loser");
        good1 = outcomeOf(build, "svc-good-1");
        assertTrue(loser.ok(), "loser passes BUILD: " + loser);
        assertEquals("ADVANCE", loser.action());
        assertTrue(good1.ok());

        // 第 2 轮 STAGING：同样全部成功。
        GroupOperationResult staging = groups.advanceGroup(group.groupId());
        assertTrue(outcomeOf(staging, "svc-loser").ok(), "loser passes STAGING");

        // 第 3 轮 CANARY：svc-loser 本轮阶段执行失败——
        // 关键断言：这一轮返回的逐成员结论里它就必须是失败，带可识别原因，
        // 不允许记成成功，也不要求调用方再查一次组详情。
        GroupOperationResult canary = groups.advanceGroup(group.groupId());
        loser = outcomeOf(canary, "svc-loser");
        testLog.info("CANARY sweep outcomes={}", canary.outcomes());
        assertEquals(false, loser.ok(), "loser must be reported failed in the very sweep it fails: " + loser);
        assertEquals("ADVANCE", loser.action(), "failure happened while executing a stage, not a skip");
        assertEquals("CANARY_HEALTH_CHECK_FAILED", loser.code(),
                "identifiable failure code returned inline");
        assertNotNull(loser.message(), "failure explanation returned inline");
        assertTrue(loser.message().contains("CANARY"), "message names the failing stage: " + loser.message());
        assertTrue(loser.message().contains("CANARY_HEALTH_CHECK_FAILED"),
                "message carries the failure code: " + loser.message());

        // 同轮其他成员不受牵连：继续推进并拿到自己的成功结论。
        for (String healthy : List.of("svc-good-1", "svc-good-2")) {
            MemberOutcome o = outcomeOf(canary, healthy);
            assertTrue(o.ok(), healthy + " keeps advancing while loser fails: " + o);
            assertEquals("ADVANCE", o.action());
        }
        // 组状态与逐成员结论一致：组 FAILED。
        assertEquals(GroupStatus.FAILED, canary.group().status());
        Release loserNow = memberOf(canary.group(), "svc-loser");
        assertEquals(ReleaseStatus.FAILED, loserNow.getStatus());
        assertEquals(StageName.CANARY, loserNow.getFailureStage());

        // 第 4 轮及后续每轮：失败终态成员只能记 SKIPPED 并保留原失败原因，
        // 不能重跑 CANARY、不能再算成功；健康成员继续走完剩余阶段。
        GroupOperationResult production = groups.advanceGroup(group.groupId());
        loser = outcomeOf(production, "svc-loser");
        assertEquals("SKIPPED", loser.action(), "terminal-failed member is never re-run: " + loser);
        assertEquals(false, loser.ok());
        assertEquals("RELEASE_FAILED", loser.code());
        assertNotNull(loser.message());
        assertTrue(loser.message().contains("CANARY"), "skip reason retains original failing stage");
        assertTrue(loser.message().contains("CANARY_HEALTH_CHECK_FAILED"),
                "skip reason retains original failure code: " + loser.message());
        for (String healthy : List.of("svc-good-1", "svc-good-2")) {
            assertTrue(outcomeOf(production, healthy).ok(), healthy + " advances PRODUCTION");
        }

        // 再来几轮，结论保持稳定：永远跳过、永远保留原失败原因。
        for (int i = 0; i < 3; i++) {
            GroupOperationResult extra = groups.advanceGroup(group.groupId());
            MemberOutcome skipped = outcomeOf(extra, "svc-loser");
            assertEquals("SKIPPED", skipped.action(), "still skipped on repeat sweep " + i);
            assertEquals("RELEASE_FAILED", skipped.code());
            assertTrue(skipped.message().contains("CANARY_HEALTH_CHECK_FAILED"));
            for (String healthy : List.of("svc-good-1", "svc-good-2")) {
                MemberOutcome ho = outcomeOf(extra, healthy);
                assertEquals("SKIPPED", ho.action(), healthy + " completed -> skipped, never re-deployed");
                assertEquals("RELEASE_ALREADY_COMPLETED", ho.code());
            }
            assertEquals(GroupStatus.FAILED, extra.group().status(),
                    "group status stays consistent with member outcomes");
        }

        ReleaseGroupView done = groups.getGroup(group.groupId());
        assertEquals(ReleaseStatus.FAILED, memberOf(done, "svc-loser").getStatus());
        assertEquals("CANARY_HEALTH_CHECK_FAILED", memberOf(done, "svc-loser").getFailureCode(),
                "persisted failure reason never overwritten");
        assertEquals(ReleaseStatus.COMPLETED, memberOf(done, "svc-good-1").getStatus());
        assertEquals(ReleaseStatus.COMPLETED, memberOf(done, "svc-good-2").getStatus());
        // 失败成员的 CANARY 只执行过一次：没有重跑、没有重复部署。
        long loserCanaryStarts = journal.readAll().stream()
                .filter(e -> e.releaseId().equals(memberOf(done, "svc-loser").getReleaseId()))
                .filter(e -> "STAGE_STARTED".equals(e.type()) && e.detail().contains("stage=CANARY"))
                .count();
        assertEquals(1L, loserCanaryStarts, "loser CANARY runs exactly once despite repeated sweeps");
        testLog.info("final state: loser reported failed in failing round and skipped ever after; peers completed");
    }

    private static MemberOutcome outcomeOf(GroupOperationResult result, String service) {
        return result.outcomes().stream()
                .filter(o -> o.serviceName().equals(service)).findFirst().orElseThrow();
    }

    @Test
    void memberRollbackViaSingleServiceApiDoesNotAffectGroupPeers() {
        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-peer", List.of(
                member("svc-peer-1", COMMIT, "peer-one", null),
                member("svc-peer-2", COMMIT, "peer-two", "fail:STAGING"))));
        for (int i = 0; i < 4; i++) {
            groups.advanceGroup(group.groupId());
        }
        Release failed = memberOf(groups.getGroup(group.groupId()), "svc-peer-2");
        assertEquals(ReleaseStatus.FAILED, failed.getStatus());

        // 组内成员就是一条普通发布：单服务接口照样能查、能判。
        Release viaSingleApi = orchestrator.get(failed.getReleaseId());
        assertEquals(ReleaseStatus.FAILED, viaSingleApi.getStatus());
        // 无历史稳定版本时回滚给出可解释拒绝，且不影响同伴。
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException.class,
                () -> orchestrator.rollback(failed.getReleaseId()));
        assertEquals("ROLLBACK_NO_STABLE_VERSION", ex.getCode());
        assertEquals(ReleaseStatus.COMPLETED,
                memberOf(groups.getGroup(group.groupId()), "svc-peer-1").getStatus());
        testLog.info("final state: peer isolation holds, single-service api composes with groups");
    }
}
