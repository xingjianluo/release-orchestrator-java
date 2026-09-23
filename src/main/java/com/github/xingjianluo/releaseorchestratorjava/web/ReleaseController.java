package com.github.xingjianluo.releaseorchestratorjava.web;

import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRecord;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.release.ReleaseOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 发布编排 REST 接口。 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseOrchestrator orchestrator;

    public ReleaseController(ReleaseOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** 触发发布（提交即完成确定性 BUILD）。 */
    @PostMapping
    public ReleaseRecord submit(@RequestBody ReleaseRequest request) {
        return orchestrator.submit(request);
    }

    /** 推进下一个阶段。 */
    @PostMapping("/{releaseId}/advance")
    public ReleaseRecord advance(@PathVariable String releaseId) {
        return orchestrator.advance(releaseId);
    }

    /** 回滚服务到上一个稳定版本。 */
    @PostMapping("/rollback")
    public ReleaseRecord rollback(@RequestParam String service) {
        return orchestrator.rollback(service);
    }

    /** 查询发布结论。 */
    @GetMapping("/{releaseId}")
    public ReleaseRecord get(@PathVariable String releaseId) {
        return orchestrator.get(releaseId);
    }
}
