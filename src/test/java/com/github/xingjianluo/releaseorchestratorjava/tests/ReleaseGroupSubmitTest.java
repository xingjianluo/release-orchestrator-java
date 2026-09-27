package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.GroupStatus;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseException;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupView;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseGroupOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布组提交：组内跨服务相同输入各自成立、组幂等、与单服务提交同源去重。 */
class ReleaseGroupSubmitTest extends AbstractOrchestratorTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Autowired
    private ReleaseGroupOrchestrator groups;

    private ReleaseRequest member(String service, String commit, String content) {
        return new ReleaseRequest(service, commit, content, null, null);
    }

    private ReleaseGroupView advanceToEnd(String groupId, int stages) {
        for (int i = 0; i < stages; i++) {
            groups.advanceGroup(groupId);
        }
        return groups.getGroup(groupId);
    }

    @Test
    void sameCommitAndContentAcrossServicesProducesIndependentReleases() throws Exception {
        // 单仓多服务典型场景：三个服务打同一个提交、源码内容一模一样。
        ReleaseGroupRequest request = new ReleaseGroupRequest("ticket-1", List.of(
                member("svc-alpha", COMMIT, "identical-source"),
                member("svc-beta", COMMIT, "identical-source"),
                member("svc-gamma", COMMIT, "identical-source")));

        ReleaseGroupView submitted = groups.submitGroup(request);
        testLog.info("group submitted id={} members={}", submitted.groupId(), submitted.releases().size());

        assertEquals(3, submitted.releases().size());
        assertEquals(GroupStatus.PENDING, submitted.status());
        // 每个服务有自己的发布身份：id 互不相同且携带服务名。
        List<String> ids = submitted.releases().stream().map(Release::getReleaseId).toList();
        assertEquals(3, ids.stream().distinct().count(), "identical inputs must not collapse into one release");
        for (Release r : submitted.releases()) {
            assertTrue(r.getReleaseId().contains(r.getServiceName()),
                    "release identity must be scoped by service: " + r.getReleaseId());
            assertNotEquals("rel-" + COMMIT.substring(0, 7), r.getReleaseId());
        }

        ReleaseGroupView done = advanceToEnd(submitted.groupId(), 4);
        assertEquals(GroupStatus.COMPLETED, done.status());
        for (Release r : done.releases()) {
            assertEquals(ReleaseStatus.COMPLETED, r.getStatus(),
                    "every member completes independently: " + r.getServiceName());
            // 内容相同 ⇒ 版本号相同；但制品按服务物化，sha 因清单含服务名而不同。
            assertEquals(done.releases().get(0).getVersion(), r.getVersion());
            // 每个服务有自己的部署指针，互不覆盖。
            var pointer = dataDir().resolve("deployments").resolve(r.getServiceName()).resolve("current.json");
            assertTrue(Files.isRegularFile(pointer), "own deployment pointer for " + r.getServiceName());
            assertTrue(Files.readString(pointer).contains(r.getVersion()));
        }
        // 制品按服务分目录存放，相同内容也不互相顶掉。
        for (Release r : done.releases()) {
            assertTrue(r.getArtifactUri().contains("artifacts/" + r.getServiceName() + "/"),
                    "artifact stored under own service dir: " + r.getArtifactUri());
        }
        testLog.info("final state: all {} members COMPLETED with independent identities", done.releases().size());
    }

    @Test
    void resubmittingSameGroupIsIdempotent() {
        ReleaseGroupRequest request = new ReleaseGroupRequest("ticket-2", List.of(
                member("svc-delta", COMMIT, "content-d"),
                member("svc-epsilon", COMMIT, "content-e")));

        ReleaseGroupView first = groups.submitGroup(request);
        int releasesAfterFirst = orchestrator.list().size();

        ReleaseGroupView second = groups.submitGroup(request);
        assertEquals(first.groupId(), second.groupId(), "same members -> same group identity");
        assertEquals(releasesAfterFirst, orchestrator.list().size(),
                "resubmission must not create new releases");
        assertEquals(1, groups.listGroups().size(), "resubmission must not create a second group");
        testLog.info("final state: idempotent resubmit groupId={} releases={}",
                second.groupId(), orchestrator.list().size());
    }

    @Test
    void groupAdoptsReleasePreviouslySubmittedIndividually() {
        // 同一输入先走单服务提交，再随组提交：必须收敛到同一条发布，不能第二次部署。
        Release solo = orchestrator.submit(member("svc-zeta", COMMIT, "content-z"));

        ReleaseGroupView group = groups.submitGroup(new ReleaseGroupRequest("ticket-3", List.of(
                member("svc-zeta", COMMIT, "content-z"),
                member("svc-eta", COMMIT, "content-eta"))));

        Release zeta = group.releases().stream()
                .filter(r -> r.getServiceName().equals("svc-zeta")).findFirst().orElseThrow();
        assertEquals(solo.getReleaseId(), zeta.getReleaseId(),
                "identical input inside a group reuses the existing release identity");
        assertEquals(2, orchestrator.list().size(), "no duplicate deployment for adopted member");
        testLog.info("final state: adopted releaseId={} groupId={}", zeta.getReleaseId(), group.groupId());
    }

    @Test
    void invalidGroupsAreRejected() {
        ReleaseException empty = assertThrows(ReleaseException.class,
                () -> groups.submitGroup(new ReleaseGroupRequest("k", List.of())));
        assertEquals("INVALID_GROUP_REQUEST", empty.getCode());

        ReleaseException dup = assertThrows(ReleaseException.class,
                () -> groups.submitGroup(new ReleaseGroupRequest("k", List.of(
                        member("svc-theta", COMMIT, "c1"),
                        member("svc-theta", "abcdef0123456789abcdef0123456789abcdef01", "c2")))));
        assertEquals("GROUP_DUPLICATE_SERVICE", dup.getCode());

        ReleaseException missing = assertThrows(ReleaseException.class,
                () -> groups.getGroup("grp-doesnotexist"));
        assertEquals("GROUP_NOT_FOUND", missing.getCode());
        testLog.info("final state: invalid group inputs rejected with stable codes");
    }
}
