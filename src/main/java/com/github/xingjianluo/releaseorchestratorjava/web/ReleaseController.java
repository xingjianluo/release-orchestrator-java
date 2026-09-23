package com.github.xingjianluo.releaseorchestratorjava.web;

import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseOrchestrator orchestrator;

    public ReleaseController(ReleaseOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public Release submit(@RequestBody ReleaseRequest request) {
        return orchestrator.submit(request);
    }

    @PostMapping("/{id}/advance")
    public Release advance(@PathVariable String id) {
        return orchestrator.advance(id);
    }

    @PostMapping("/{id}/rollback")
    public Release rollback(@PathVariable String id) {
        return orchestrator.rollback(id);
    }

    @GetMapping("/{id}")
    public Release get(@PathVariable String id) {
        return orchestrator.get(id);
    }

    @GetMapping
    public List<Release> list() {
        return orchestrator.list();
    }
}
