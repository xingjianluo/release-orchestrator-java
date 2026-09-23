# 本地可复现的发布编排器（Release Orchestrator）

用**本地状态与内容寻址制品**模拟构建、分阶段发布与回滚，使**并发触发、重复执行、故障恢复**
下的发布结论一致且可解释。全程不依赖外部服务、真实凭据或付费资源，`mvn test` 即可离线复现。

- Java 21 / Spring Boot 3.4.x，单进程、每服务一把内存锁
- 状态与制品落盘为本地文件（原子写），进程重启自动恢复
- 全链路确定性：相同输入 ⇒ 相同版本结论、相同制品、相同发布标识与健康结论

---

## 1. 版本与制品规则

### 1.1 版本号

- 版本由请求显式声明，必须符合语义版本：`MAJOR.MINOR.PATCH[-预发布标识]`
  （正则 `^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$`）。
  非法格式以 `INVALID_VERSION` 拒绝。
- **非法版本流转**（同样以 `INVALID_VERSION` 拒绝，原因信息可区分）：
  - 版本重复：同一服务下该版本已在任意一次发布（无论成功/失败/回滚）中使用；
  - 版本降级：新版本不高于当前线上版本（按 SemVer 核心号比较，预发布版本低于同号正式版）。

### 1.2 确定性标识

仅由以下字段规范化后派生（`VersionGenerator`）：

```
contentKey = "service=<s>|version=<v>|sourceRef=<ref>|notes=<排序后的变更说明>"
releaseId  = "rel-" + SHA256(contentKey)[0:16]
```

- 相同输入（含变更说明顺序不同）一定得到相同 `releaseId` 与制品；不同输入一定不同。
- `failAtStage`（故障注入钩子）**不参与**任何标识计算。

### 1.3 制品（内容寻址、绝不静默覆盖）

- 制品字节由内容键确定性构建，**不含时间戳/随机数**，同输入字节级一致。
- 校验和 = 内容的 SHA-256；`artifactId = "art-" + checksum[0:24]`。
- 落盘：`<artifact-dir>/<artifactId>.bin`（内容）+ `<artifactId>.json`（元数据），
  写入采用“临时文件 + `ATOMIC_MOVE`”。
- 已存在的制品：
  - 内容一致 ⇒ **复用**（`reused=true`），不重复写入、不重复构建；
  - 内容不一致（文件被篡改）⇒ 以 `ARTIFACT_INTEGRITY_FAILED` 拒绝，**绝不静默覆盖**。
- 每次阶段部署前都重新读取磁盘内容并校验 SHA-256，校验失败以
  `ARTIFACT_INTEGRITY_FAILED` 拒绝，发布进入 `FAILED`。

---

## 2. 发布状态机

```
                 构建(BUILD)成功
  PENDING ───────────────────────► IN_PROGRESS ──STAGING──► ... ──FULL──► STABLE
     │                                │
     │ 启动恢复(BUILD结果未知)         │ 任一阶段健康/完整性失败
     ▼                                ▼
  FAILED ◄──────────────────────  FAILED（可解释终态：failureStage + reasonCode + reason）
                                   │
                                   │ rollback 开始
                                   ▼
                                ROLLING_BACK ──回滚动作完成──► ROLLED_BACK
```

- 阶段严格有序：`BUILD → STAGING → CANARY → FULL`。下一阶段由持久化的
  `completedStages` 推导，前一阶段未通过不能进入下一阶段。
- 非法跳转（状态不符、跳阶段）以 `INVALID_STAGE_TRANSITION` 拒绝。
- `FULL` 成功后进入终态 `STABLE`，服务线上指针才切换到新版本。
- 非 `IN_PROGRESS` 的发布一律不得推进：`FAILED / ROLLING_BACK / ROLLED_BACK / STABLE`
  都以 `RELEASE_NOT_ADVANCEABLE` 拒绝（重复推进/并发推进的多余请求由此被挡下）。

### 并发与重复执行的一致性

- 触发发布即同步完成确定性 BUILD。同一 `releaseId` 重复提交以 `DUPLICATE_RELEASE`
  拒绝（并返回既有状态），绝不产生第二条发布记录。
- 同一服务存在进行中发布（`activeReleaseId`）时，新触发以 `DUPLICATE_RELEASE` 拒绝。
- 同一服务上的提交/推进/回滚都在**每服务锁**内串行；跨服务互不阻塞。
- 推进是幂等的：阶段是否完成只认持久化的 `completedStages`。并发推进时每个阶段恰好
  生效一次，已达 `STABLE` 后其余推进以 `RELEASE_NOT_ADVANCEABLE` 拒绝——不重复部署、
  不跳阶段、状态不错乱。

---

## 3. 失败、回滚与恢复语义

### 3.1 失败终态

任一阶段失败（健康准入或制品完整性）都会：

1. 将发布置为 `FAILED`，记录 `failureStage`、`failureReasonCode`、`failureReason`；
2. 释放服务占用（`activeReleaseId` 清空），登记 `pendingRollbackReleaseId`；
3. 追加 `FAILED` 变更历史。

线上版本指针在 FULL 成功前**不会移动**，因此失败后线上仍是上一个稳定版本。

可区分的原因码（`ErrorCode`）：

| 原因码 | 触发场景 |
| --- | --- |
| `INVALID_VERSION` | 版本格式非法 / 版本重复 / 版本降级 |
| `DUPLICATE_RELEASE` | 相同输入重复提交 / 服务已有进行中发布 |
| `ARTIFACT_INTEGRITY_FAILED` | 制品缺失、被篡改、校验和不符、拒绝覆盖 |
| `RELEASE_NOT_FOUND` | 查询/操作不存在的发布（HTTP 404） |
| `INVALID_STAGE_TRANSITION` | 非法阶段跳转 / 启动恢复标记 |
| `HEALTH_CHECK_FAILED` | 阶段健康准入未通过 |
| `RELEASE_NOT_ADVANCEABLE` | 失败/回滚中/已稳定的发布被再次推进 |
| `ROLLBACK_CONFLICT` | 回滚与发布交叉执行 / 重复回滚 / 无稳定版本可退 |

### 3.2 回滚

- 存在进行中发布时回滚 ⇒ `ROLLBACK_CONFLICT`（回滚与发布不得交叉执行）。
- 存在“失败待回滚”发布时：回滚确认恢复到**当前仍在线**的上一个稳定版本（指针本就未动），
  失败发布走 `ROLLING_BACK → ROLLED_BACK`；重复回滚已 `ROLLED_BACK` 的发布以
  `ROLLBACK_CONFLICT` 拒绝。
- 没有待回滚发布时：对已上线版本执行 `current` 与 `previous` 稳定指针交换；
  若不存在上一个稳定版本则以 `ROLLBACK_CONFLICT` 拒绝。
- 回滚前会校验目标制品完整性；回滚动作（服务状态落盘）先于“回滚完成”结论发布。
- 变更历史 append-only，任何回滚都不删除或改写既有条目。

### 3.3 进程中断后的启动恢复（`RecoveryRunner` / `recoverInterruptedReleases`）

启动时扫描全部发布记录，确定性地解析未决状态：

- `PENDING`（BUILD 结果未知）⇒ `FAILED`（失败阶段 BUILD，原因可解释，可回滚）；
- `IN_PROGRESS`（中途崩溃）⇒ `FAILED`，`failureStage = 下一未完成阶段`，
  已完成阶段保留；同时在服务状态登记 `pendingRollbackReleaseId`；
- `ROLLING_BACK`（回滚动作先落盘、完成标记前崩溃）⇒ `ROLLED_BACK`；
- 清理服务上残留的 `activeReleaseId` 占用。

已上线的稳定版本与变更历史不受恢复影响。

---

## 4. 关键配置项

`src/main/resources/application.properties`，均可通过环境变量覆盖，默认全部位于本地
`target/` 下：

| 配置 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `release.state-dir` | `RELEASE_STATE_DIR` | `target/release-state` | 发布记录 `releases/` 与服务状态 `services/` 根目录 |
| `release.artifact-dir` | `RELEASE_ARTIFACT_DIR` | `target/release-state/artifacts` | 内容寻址制品目录 |

---

## 5. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/releases` | 触发发布（提交即完成确定性 BUILD），请求体见下 |
| `POST /api/releases/{releaseId}/advance` | 推进到下一阶段 |
| `POST /api/releases/rollback?service=<服务>` | 将服务恢复到上一个稳定版本 |
| `GET  /api/releases/{releaseId}` | 查询发布结论 |

请求体示例：

```json
{
  "service": "orders",
  "version": "1.2.0",
  "sourceRef": "commit-or-tag",
  "changeNotes": ["feat: x", "fix: y"],
  "failAtStage": "CANARY"
}
```

`failAtStage` 为本地**故障注入钩子**：取值 `BUILD/STAGING/CANARY/FULL` 之一时，
该阶段健康检查确定性失败；为空表示全部通过。错误响应统一为
`{"errorCode": "...", "message": "..."}`（冲突 409，不存在 404）。

---

## 6. 本地验证方法

要求：JDK 21+、Maven 3.6+，无需联网（依赖已在本地仓库时）。

```bash
mvn test
```

测试矩阵（共 22 个，全部离线、确定性、可反复重跑）：

| 测试类 | 覆盖点 |
| --- | --- |
| `VersionGeneratorTest` | 相同输入同标识、故障钩子不影响标识、非法版本拒绝原因码 |
| `ArtifactStoreTest` | 确定性构建与复用、不同内容不同制品、篡改/缺失 ⇒ 完整性失败 |
| `ReleaseHappyPathIntegrationTest` | 四阶段顺序发布、重复提交拒绝、版本降级/重复版本拒绝 |
| `FailureAndRollbackIntegrationTest` | CANARY/FULL 失败终态、失败不可再推进、回滚到上一稳定版本、重复回滚、稳定版本指针交换 |
| `ConcurrencyIntegrationTest` | 12 线程并发重复提交仅 1 次成功；16 线程并发推进不跳阶段/不重复部署 |
| `ArtifactIntegrityIntegrationTest` | 发布途中制品被篡改 ⇒ 部署准入失败且终态 FAILED、仍可回滚 |
| `RecoveryIntegrationTest` | 构造 IN_PROGRESS 崩溃现场，重建组件并恢复 ⇒ FAILED + 占用清理 + 可回滚 + 历史不丢 |
| `ReleaseControllerTest` | REST 层错误码可区分（409/404） |

日志中会打印**输入**（service/version/sourceRef/failAtStage/派生 releaseId）、
**关键决策**（每阶段“决策=通过/失败”及原因码）与**最终状态**（状态机状态、已完成阶段、
线上版本）。集成测试工作目录为 `target/test-workdir/<测试类名>/`，可直接查看 JSON 落盘。

手动运行应用：

```bash
mvn spring-boot:run
curl -X POST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -d '{"service":"demo","version":"1.0.0","sourceRef":"r1","changeNotes":["init"]}'
```
