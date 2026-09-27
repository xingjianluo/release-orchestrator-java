package com.github.xingjianluo.releaseorchestratorjava.web;

import com.github.xingjianluo.releaseorchestratorjava.model.GroupOperationResult;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseGroupView;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseGroupOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/release-groups")
public class ReleaseGroupController {

    private final ReleaseGroupOrchestrator groupOrchestrator;

    public ReleaseGroupController(ReleaseGroupOrchestrator groupOrchestrator) {
        this.groupOrchestrator = groupOrchestrator;
    }

    @PostMapping
    public ReleaseGroupView submit(@RequestBody ReleaseGroupRequest request) {
        return groupOrchestrator.submitGroup(request);
    }

    @PostMapping("/{id}/advance")
    public GroupOperationResult advance(@PathVariable String id) {
        return groupOrchestrator.advanceGroup(id);
    }

    @PostMapping("/{id}/rollback")
    public GroupOperationResult rollback(@PathVariable String id) {
        return groupOrchestrator.rollbackGroup(id);
    }

    @GetMapping("/{id}")
    public ReleaseGroupView get(@PathVariable String id) {
        return groupOrchestrator.getGroup(id);
    }

    @GetMapping
    public List<ReleaseGroupView> list() {
        return groupOrchestrator.listGroups();
    }
}
