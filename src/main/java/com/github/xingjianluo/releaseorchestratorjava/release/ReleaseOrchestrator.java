package com.github.xingjianluo.releaseorchestratorjava.release;

import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseStatus;

import java.util.List;

/** 发布编排应用服务：提交、逐阶段推进、回滚、查询。 */
public interface ReleaseOrchestrator {

    /** 一次中断调和的结果。 */
    record RecoveryResult(String releaseId, StageName stage, ReleaseStatus newStatus) {
    }

    Release submit(ReleaseRequest request);

    /** 推进一个阶段（严格按序）；重复推进幂等返回已通过状态。 */
    Release advance(String releaseId);

    Release rollback(String releaseId);

    Release get(String releaseId);

    List<Release> list();

    /** 扫描全部发布，将进程中断时残留的 RUNNING 阶段调和为 FAILED 终态，返回受影响发布。 */
    List<RecoveryResult> recoverInterrupted();
}
