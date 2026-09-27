package com.github.xingjianluo.releaseorchestratorjava.group.web;

import com.github.xingjianluo.releaseorchestratorjava.group.model.GroupMemberResult;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroup;
import com.github.xingjianluo.releaseorchestratorjava.group.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.group.release.ReleaseGroupOrchestrator;
import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布组 HTTP 接口。原单服务 /api/releases 保持不变，二者完全兼容。
 */
@RestController
@RequestMapping("/api/release-groups")
public class ReleaseGroupController {

    private final ReleaseGroupOrchestrator orchestrator;

    public ReleaseGroupController(ReleaseGroupOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ReleaseGroup submit(@RequestBody ReleaseGroupRequest request) {
        return orchestrator.submit(request);
    }

    @PostMapping("/{id}/advance")
    public List<GroupMemberResult> advance(@PathVariable String id) {
        return orchestrator.advance(id);
    }

    @PostMapping("/{id}/rollback")
    public List<GroupMemberResult> rollback(@PathVariable String id) {
        return orchestrator.rollback(id);
    }

    @PostMapping("/{id}/members/{serviceName}/rollback")
    public Release rollbackMember(@PathVariable String id, @PathVariable String serviceName) {
        return orchestrator.rollbackMember(id, serviceName);
    }

    @GetMapping("/{id}")
    public ReleaseGroup get(@PathVariable String id) {
        return orchestrator.get(id);
    }

    @GetMapping
    public List<ReleaseGroup> list() {
        return orchestrator.list();
    }
}
