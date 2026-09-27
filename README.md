# 本地发布编排器（Release Orchestrator）

一个**完全本地、离线可复现**的发布编排流程：用本地文件状态与确定性制品，模拟
“构建 → 分阶段发布（STAGING → CANARY → PRODUCTION）→ 健康准入 → 失败回滚”，
并保证在**并发触发、重复执行、进程中断重启**三类扰动下，发布结论一致、可解释、历史不丢失。

不依赖任何外部服务、数据库、真实凭据或付费资源：状态、制品、日志全部落在本地目录。

---

## 1. 快速开始

```bash
# 离线构建 + 运行全部测试（依赖已在本地 Maven 缓存）
mvn -o clean test

# 启动服务（默认监听 8080）
mvn -o spring-boot:run
```

提交一次发布并逐阶段推进（`faultInjection` 留空表示全流程健康）：

```bash
curl -s -X POST localhost:8080/api/releases -H 'Content-Type: application/json' -d '{
  "serviceName":"billing-api",
  "commit":"0123456789abcdef0123456789abcdef01234567",
  "sourceContent":"hello release",
  "idempotencyKey":"ticket-1001",
  "faultInjection":null
}'

# 依次推进 4 次：BUILD -> STAGING -> CANARY -> PRODUCTION
curl -s -X POST localhost:8080/api/releases/<releaseId>/advance

# 失败后回滚到上一个稳定版本
curl -s -X POST localhost:8080/api/releases/<releaseId>/rollback
```

### 1.1 发布组（单仓多服务，一次带一组服务发布）

单仓里多个服务经常打同一个提交、甚至源码内容都一样。发布组在单服务能力之上
**再加一层编排**：一次提交一组服务，组内每个服务各自拥有独立发布身份、各走各的阶段、
故障隔离、独立回滚。

```bash
curl -s -X POST localhost:8080/api/release-groups -H 'Content-Type: application/json' -d '{
  "idempotencyKey":"release-train-2026-09-28",
  "members":[
    {"serviceName":"billing-api","commit":"0123456789abcdef0123456789abcdef01234567","sourceContent":"same source","faultInjection":null},
    {"serviceName":"edge-api",   "commit":"0123456789abcdef0123456789abcdef01234567","sourceContent":"same source","faultInjection":"fail:CANARY"}
  ]
}'

# 一轮并行推进组内所有可推进成员（各推进一个阶段，不同服务并行）
curl -s -X POST localhost:8080/api/release-groups/<groupId>/advance

# 组级回滚：只回滚组内 FAILED 的成员到各自的上一个稳定版本
curl -s -X POST localhost:8080/api/release-groups/<groupId>/rollback

# 只回滚组里某一个服务（互不牵连）
curl -s -X POST localhost:8080/api/release-groups/<groupId>/members/billing-api/rollback

# 查询组（含每个成员的独立 releaseId、版本与实时状态）
curl -s localhost:8080/api/release-groups/<groupId>
```

发布组里的每个成员同时也是一条**普通发布**，可以直接用
`/api/releases/<memberReleaseId>/advance|rollback` 单独驱动；组级操作与单服务操作
共用同一套按发布身份的锁，交叉执行也是安全的（详见第 10 节）。

---

## 2. 版本与制品规则（确定性）

输入三元组 `(serviceName, commit, sourceContent)` 唯一决定一切产物，过程**不含时间戳或随机量**：

| 产物 | 派生规则 |
|------|----------|
| contentHash | `sha256(sourceContent)`（完整 64 位十六进制） |
| version | `1.0.0-<commit 前 7 位>+<contentHash 前 12 位>` |
| 制品字节 | 固定格式清单头（service/commit/version/content-sha256）+ 原始 sourceContent |
| artifactSha256 | 对“制品字节”整体取 SHA-256 |
| 制品路径 | `<dataDir>/artifacts/<serviceName>/<artifactSha256>.bin`（内容寻址） |
| releaseId | `rel-<serviceName>-<commit 前 7 位>-<contentHash 前 12 位>` |

因此：**相同服务的相同输入 ⇒ 相同 releaseId / version / artifactSha256 / 路径**；
任一输入变化则全部变化。releaseId 中显式包含 serviceName，所以**不同服务即使
commit、sourceContent 完全相同，也是各自独立的发布身份**，各自有独立状态、锁、
阶段进度、部署指针与历史，互不顶掉（这是发布组隔离的基础）。

### 非法与重复处理（静默覆盖被明确禁止）

- `serviceName` 必须匹配 `[a-z0-9][a-z0-9-]{0,63}`，否则 `INVALID_SERVICE_NAME`；
- `commit` 必须为 7–40 位小写十六进制，否则 `INVALID_COMMIT`；
- 相同 `(serviceName, commit, contentHash)` 的发布已存在时，再次提交被拒绝：
  **`DUPLICATE_SUBMISSION`（HTTP 409）**，响应中带回既有 releaseId 与状态——
  相同输入通过**复用**表达，而不是重新提交；
- 制品落盘为内容寻址：
  - 目标路径已存在且字节一致 ⇒ **直接复用**，不重写；
  - 目标路径已存在但字节摘要不一致 ⇒ 拒绝 **`ARTIFACT_CONTENT_CONFLICT`**，绝不静默覆盖；
  - 写入采用“临时文件 + 原子 move”，进程中断只会留下 `.tmp-*`，不会出现半成品制品；
- 每个阶段准入前都对磁盘制品重算 SHA-256（并核对字节数），损坏即
  **`ARTIFACT_CHECKSUM_MISMATCH`**，文件缺失为 `ARTIFACT_MISSING`。

---

## 3. 发布状态机

```
                         提交
PENDING ──advance(BUILD)──► IN_PROGRESS ──逐阶段推进──► COMPLETED（登记为稳定版本）
                              │
                              │ 任一阶段健康/校验失败
                              ▼
                            FAILED ◄──────────────┐
                              │                   │ 回滚过程中进程中断，
                 rollback     │ ROLLING_BACK      │ 重启调和回 FAILED 等待重试
                              ▼                   │
                          ROLLED_BACK ─────────────┘
```

阶段严格按 `BUILD → STAGING → CANARY → PRODUCTION` 顺序执行（由
`Release.nextStage()` 决定下一个未通过阶段，天然无法跳阶段）：

- 每次 `advance` 只推进**一个**阶段；
- 阶段开始前先把该阶段状态 `RUNNING` 与发布 `IN_PROGRESS` **落盘**，再执行；
- `BUILD`：确定性物化制品 + 完整性校验 + 构建健康检查；
- `STAGING / CANARY / PRODUCTION`：**先重算制品摘要**，再做该阶段健康检查，
  通过后记一次部署事件；
- `PRODUCTION` 通过：原子更新
  `<dataDir>/deployments/<serviceName>/current.json`（当前生效指针），
  并把版本登记进稳定版本登记册。

### 健康准入（确定性、可复现）

`DeterministicHealthProbe` 完全离线，结论由发布时携带的 `faultInjection` 决定，
相同 `(version, stage, faultInjection)` 永远得到相同结论：

| faultInjection | 含义 |
|----------------|------|
| 空 | 所有阶段健康 |
| `fail:BUILD` / `fail:STAGING` / `fail:CANARY` / `fail:PRODUCTION` | 指定阶段必然失败 |
| `fail` | 所有阶段失败 |

`faultInjection` **不参与**版本/制品的确定性计算，只影响健康结论。

### 非法状态流转（均为显式拒绝，HTTP 409）

| 场景 | 错误码 |
|------|--------|
| 对 FAILED 发布继续推进 | `RELEASE_FAILED`（消息携带失败阶段与失败码） |
| 对 ROLLED_BACK 发布推进 | `RELEASE_ROLLED_BACK`（带回回滚目标版本） |
| 对 ROLLING_BACK 发布推进 | `RELEASE_ROLLING_BACK` |
| 对 COMPLETED 发布再推进 | `RELEASE_ALREADY_COMPLETED` |
| 并发操作拿不到锁（超时） | `RELEASE_BUSY` |
| 尝试跳过阶段（理论防线） | `STAGE_ORDER_VIOLATION` |

---

## 4. 失败与回滚语义

### 失败终态

任一阶段失败，发布被收敛为**可解释的终态**：

- `status = FAILED`，记录 `failureStage / failureCode / failureReason`；
- 失败阶段 `StageStatus = FAILED`，后续阶段保持 `PENDING`（不会被跳过）；
- 写入仅追加事件 `STAGE_FAILED`、`RELEASE_FAILED`；
- 失败发布不能被再次推进，只能回滚或用新输入发起新发布。

失败码可区分，例如 `BUILD_HEALTH_CHECK_FAILED`、`CANARY_HEALTH_CHECK_FAILED`、
`ARTIFACT_CHECKSUM_MISMATCH`、`RECOVERED_AFTER_INTERRUPT`。

### 回滚

- 仅 `FAILED` / `IN_PROGRESS` 状态允许回滚；`COMPLETED` 回滚被拒绝
  （`ROLLBACK_NOT_ALLOWED`），`ROLLED_BACK` 上重复回滚为**幂等成功**，
  不重复变更部署指针、不重复写历史；
- 回滚目标来自**稳定版本登记册**
  （`<dataDir>/registry/<serviceName>.txt`，只有完整通过 PRODUCTION 的版本才登记），
  取“上一个稳定版本”；没有任何稳定版本时拒绝为 `ROLLBACK_NO_STABLE_VERSION`；
- 回滚原子地把当前部署指针切回稳定版本的 `(version, artifactUri, artifactSha256)`，
  状态置 `ROLLED_BACK` 并记录 `rolledBackFromVersion / rolledBackToVersion`；
- 制品永不删除，历史制品仍可被审计与重新指向。

### 进程中断与重启恢复

`advance` / `rollback` 的关键中间态都会先落盘。启动时（或手动调用恢复）执行调和：

- 残留 `RUNNING` 的阶段 ⇒ 置 `FAILED`，失败码 **`RECOVERED_AFTER_INTERRUPT`**，
  原因明确写明“阶段在进程中断时停留在 RUNNING，重启时调和为 FAILED”，
  随后可正常回滚；
- 残留 `ROLLING_BACK` ⇒ 调和回 `FAILED`（同样带 `RECOVERED_AFTER_INTERRUPT`），
  等待重试回滚；
- 恢复本身幂等：再次扫描为空操作。

事件日志为单行 JSON 仅追加文件，读取时跳过末尾可能被截断的半行，
因此**已发布版本与变更历史不会丢失或错乱**；事件 `seq` 全局严格连续。

---

## 5. 一致性机制（并发 / 重复 / 交叉）

- 每次 `submit / advance / rollback / recover` 都在临界区内执行：
  **按 releaseId 获取进程内 `ReentrantLock` + JVM 文件锁（`locks/<id>.lock`，可重入）
  → 从磁盘加载最新状态 → 判定 → 原子写回状态 + 仅追加事件 → 释放锁**；
- 锁等待超时返回 `RELEASE_BUSY`，避免调用方无限挂起；
- 状态文件 `releases/<releaseId>.json` 采用临时文件 + 原子替换；
- 事件追加在文件锁保护下进行并递增全局 `seq`；
- 因此并发提交只会有一个赢家（其余 `DUPLICATE_SUBMISSION`/`RELEASE_BUSY`），
  并发推进下每个阶段 `STAGE_STARTED` 与部署恰好一次，不重复部署、不跳阶段、状态不错乱。

---

## 6. 本地数据目录布局（默认 `target/release-data`）

```
<dataDir>/
├── artifacts/<serviceName>/<artifactSha256>.bin   # 内容寻址制品（原子写入）
├── releases/<releaseId>.json                      # 每个发布（含组成员）的完整状态（原子替换）
├── groups/<groupId>.json                          # 每个发布组的元数据与成员身份快照（原子替换）
├── journal/events.log                             # 仅追加事件日志（单行 JSON，全局有序）
├── journal/events.lock                            # 事件日志跨进程锁
├── locks/<key>.lock                               # 发布(releaseId)/发布组(group:<id>)操作跨进程锁
├── registry/<serviceName>.txt / .lock             # 稳定版本登记册及锁（按服务各自独立）
└── deployments/<serviceName>/current.json         # 当前生效部署指针（原子替换，按服务各自独立）
```

状态文件损坏时抛 `STATE_CORRUPT` 并保留原文件，**绝不静默重建**。

---

## 7. 关键配置项（`src/main/resources/application.properties`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `release.orchestrator.data-dir` | `target/release-data` | 本地状态与制品根目录；也可用环境变量 `RELEASE_DATA_DIR` 覆盖 |
| `release.orchestrator.lock-timeout-millis` | `5000` | 同一发布/发布组并发操作的锁等待毫秒，超时返回 `RELEASE_BUSY` |
| `release.orchestrator.recover-on-startup` | `true` | 启动时是否自动调和中断状态（先成员发布、后发布组） |
| `release.orchestrator.group-advance-parallelism` | `8` | 发布组一轮并行推进成员时的最大并发线程数（不同服务并行；同一服务仍按其发布锁串行） |

---

## 8. HTTP 接口与错误码

| 方法与路径 | 说明 |
|------------|------|
| `POST /api/releases` | 提交发布（body 为 ReleaseRequest） |
| `POST /api/releases/{id}/advance` | 推进下一个阶段 |
| `POST /api/releases/{id}/rollback` | 回滚到上一个稳定版本 |
| `GET  /api/releases/{id}` | 查询单个发布（含各阶段状态与失败原因） |
| `GET  /api/releases` | 列出全部发布 |
| `POST /api/release-groups` | 提交发布组（body 为 ReleaseGroupRequest） |
| `POST /api/release-groups/{id}/advance` | 并行推进组内所有可推进成员一个阶段，返回逐成员结果 |
| `POST /api/release-groups/{id}/rollback` | 回滚组内所有 FAILED 成员到各自上一个稳定版本，返回逐成员结果 |
| `POST /api/release-groups/{id}/members/{serviceName}/rollback` | 只回滚组内指定服务 |
| `GET  /api/release-groups/{id}` | 查询单个发布组（含每个成员身份与实时状态） |
| `GET  /api/release-groups` | 列出全部发布组 |

错误响应统一为 `{ timestamp, releaseId, code, message }`。HTTP 状态：
`404`（`RELEASE_NOT_FOUND` / `GROUP_NOT_FOUND` / `GROUP_MEMBER_NOT_FOUND`）、
`409`（并发冲突 / 非法流转 / 重复提交 / 组内重复服务）、
`400`（入参或制品校验等其余业务错误）。`code` 是稳定可区分的错误码。

发布组相关新增错误码：

| 错误码 | HTTP | 含义 |
|--------|------|------|
| `DUPLICATE_SUBMISSION` | 409 | 相同幂等键的组已存在；或组内某服务的相同输入已在别处（另一组/单服务）发布 |
| `DUPLICATE_SERVICE_IN_GROUP` | 409 | 同一个服务在一个组里出现了多次（一个组对每个服务只发布一次） |
| `INVALID_GROUP` | 400 | 组成员为空或超过单次上限（200） |
| `INVALID_IDEMPOTENCY_KEY` | 400 | 幂等键不符合 `[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}` |
| `GROUP_NOT_FOUND` / `GROUP_MEMBER_NOT_FOUND` | 404 | 组不存在 / 服务不属于该组 |
| `RELEASE_BUSY` | 409 | 组或某成员正被其它触发占用（锁等待超时） |

---

## 9. 本地验证方法（离线、确定性）

```bash
mvn -o clean test
```

测试位于 `src/test/.../tests`，每个测试类使用独立的 `target/test-data/<uuid>` 临时目录，
用例前清空，可重复运行；日志中会打印**输入、关键决策、最终状态**。

| 测试类 | 覆盖内容 |
|--------|----------|
| `DeterministicVersionArtifactTest` | 相同输入相同版本/制品、不同输入不同产物、重复提交拒绝、入参校验码、制品复用、冲突不覆盖、损坏制品校验失败 |
| `StagedReleaseHealthGateTest` | 严格逐阶段推进、健康门禁失败阻断后续阶段、BUILD 失败独立错误码、终态不可再推进 |
| `RollbackFailureTest` | 回滚到上一稳定版并切换指针、无稳定版拒绝、COMPLETED 不可回滚、重复回滚幂等、ROLLED_BACK 不可推进 |
| `ConcurrencyTest` | 12 线程并发同输入提交（恰好 1 个成功）、16 线程并发推进（每阶段恰好开始/部署一次、全部 PASSED、最终 COMPLETED） |
| `InterruptionRecoveryTest` | 模拟阶段 RUNNING 中“被 kill”，用**全新 Spring 上下文**从同一磁盘重启：调和为 FAILED(RECOVERED_AFTER_INTERRUPT)、可回滚、重复恢复幂等、事件序号连续且历史完整；回滚中断后调和回 FAILED 可重试 |
| `ArtifactGateAtStageTest` | BUILD 后篡改磁盘制品，STAGING 准入前校验失败并收敛为可解释终态 |
| `ReleaseControllerWebTest` | HTTP 端到端：确定性提交、409/400/404 错误码区分、完整旅程后重复推进 409 |
| `ReleaseGroupBasicTest` | 组内同提交/同内容的不同服务各自独立身份、各自走完全部阶段、重复组提交 409 且可复用、组与单服务同输入全局只部署一次 |
| `ReleaseGroupFailureIsolationTest` | 一个成员阶段失败（带失败码/原因）只影响它自己、其余成员继续到 COMPLETED、组终态 COMPLETED_WITH_FAILURES；组级回滚只动 FAILED 成员并各回各的稳定版、COMPLETED 成员不牵连、回滚幂等；按服务单独回滚 |
| `ReleaseGroupConcurrencyTest` | 12 线程并发提交同键组（恰好 1 个成功，磁盘每服务仅 1 条状态）；36 个不同服务并行推进、阶段各恰好一次、状态不串台、整体并行完成 |
| `ReleaseGroupCrossTriggerConcurrencyTest` | 同一服务被“组提交”与“单服务提交”各 8 线程并发触发：全局恰好 1 次部署，其余全部 DUPLICATE_SUBMISSION |
| `ReleaseGroupRecoveryTest` | 全新上下文重启：组成员 RUNNING 被调和为 FAILED 而不影响其他成员、组成员状态文件缺失时按身份确定性补齐、组状态刷新为 DEGRADED、重复恢复幂等、调和后可继续推进/按服务回滚 |
| `ReleaseGroupControllerWebTest` | 发布组 HTTP 端到端：提交/推进/回滚/查询旅程、409 重复、400 非法组与服务名、组内重复服务 409、未知组 404 |

### 手动复现一次“失败 → 重启恢复 → 回滚”

1. 用 `faultInjection:"fail:CANARY"` 提交，连续 `advance` 至 CANARY 失败（`FAILED`）；
2. 直接把 `releases/<id>.json` 中 CANARY 状态改为 `RUNNING` 并把 `status` 改为
   `IN_PROGRESS`（模拟阶段执行中进程被 kill），重新启动应用，观察启动日志中的
   `RECOVERED_AFTER_INTERRUPT` 调和；
3. 先完整发布一个健康版本，再制造一个失败发布，调用 `/rollback`，
   检查 `deployments/<serviceName>/current.json` 是否切回上一个稳定版本。

---

## 10. 发布组（Release Group）语义与隔离规则

发布组是单服务编排之上的**一层聚合**，不改变单服务的任何既有规则。

### 10.1 提交与身份

- 请求体 `ReleaseGroupRequest { idempotencyKey, members[] }`，每个成员是
  `{ serviceName, commit, sourceContent, faultInjection }`，至少 1 个、单次上限 200 个；
- `groupId = grp-<idempotencyKey>`。幂等键可显式给出；省略时由“整组有序输入”
  （service/commit/contentHash 序列）取 SHA-256 前 16 位确定性派生，
  因此相同整组输入永远解析到同一个组；
- 组内每个成员生成**独立 releaseId**（含 serviceName，见第 2 节），
  并各自在 `releases/` 下落一条独立发布。**即使两个服务用完全相同的 commit 与
  sourceContent、确定性 version 字符串相同，它们也是两条互不相干的发布**：
  独立状态文件、独立锁、独立阶段进度、独立稳定版本登记册、独立部署指针；
- 一个组里同一个 `serviceName` 只能出现一次（`DUPLICATE_SERVICE_IN_GROUP`）；
- 组提交是“all-or-nothing”的：提交时先按成员 releaseId **固定排序**一次性持有
  全部成员的发布锁做冲突预检，任何一个成员已在别处（另一组 / 单服务）存在相同输入，
  整组被拒（`DUPLICATE_SUBMISSION`），**不会只建一半**。

### 10.2 组内故障隔离

- `POST /groups/{id}/advance` 一轮并行推进组内所有成员各一个阶段
  （并发度由 `group-advance-parallelism` 控制）；
- 每个成员在**自己的发布锁**内推进，失败被**逐成员收敛**，只体现在该成员的结果
  （`errorCode/message`）与其 Release 终态上，**不向上扩散、不阻断其他成员**；
- 已是终态（FAILED / ROLLED_BACK / COMPLETED）的成员在后续轮次被跳过，
  保留其可解释终态；其他成员继续按自己的节奏推进；
- 组状态由成员权威状态派生：`PENDING / IN_PROGRESS / DEGRADED（已有失败但仍有成员在推进）/
  COMPLETED（全部完成）/ COMPLETED_WITH_FAILURES（全部到终态且含失败）`。
  组状态只是快照，随时可以从成员 Release 重新推导。

### 10.3 按服务回滚

- 组级 `/rollback` 只回滚组内 `FAILED`（及历史 `IN_PROGRESS`）的成员，
  每个成员回滚到**它自己服务**的上一个稳定版本（各服务有独立的
  `registry/<serviceName>.txt`，互不共享）；
- `COMPLETED` 成员不被牵连；`ROLLED_BACK` 成员重复回滚为幂等成功，不重复切指针；
- 也可用 `/members/{serviceName}/rollback` 只回滚某一个服务；
- 某个成员“没有稳定版本可回滚”只让它自己得到 `ROLLBACK_NO_STABLE_VERSION` 结果，
  不影响其他成员的回滚。

### 10.4 并发、幂等与规模

- 同一服务的重复/并发触发——无论来自单服务接口、同一个组还是不同组——都由
  “确定性 releaseId + 发布锁 + DUPLICATE_SUBMISSION”保证**只产生一次部署**，
  不会多发、不会跳阶段（阶段仍严格 BUILD→STAGING→CANARY→PRODUCTION）；
- 不同服务的锁互不相同，组内不同服务并行推进，不会因为共用一套编排互相卡住；
- 几十个服务一批提交时按 `group-advance-parallelism` 并行，整体耗时随服务数
  近似线性增长（受单机 I/O 影响）；每个发布的 `STAGE_STARTED/STAGE_DEPLOYED`
  在并发性下仍恰好一次，事件 `seq` 全局连续，状态不串台、不互相覆盖。

### 10.5 中断重启恢复（两层，顺序执行）

1. **成员发布层**：组里每个成员都是普通发布。残留 `RUNNING` 阶段调和为
   `FAILED(RECOVERED_AFTER_INTERRUPT)`，残留 `ROLLING_BACK` 调和回 `FAILED`；
2. **发布组层**：补齐“组元数据已落盘、某个成员 Release 尚未落盘”这一中断窗口里
   缺失的成员（用成员输入快照按相同身份确定性重建），再用成员权威状态刷新组状态。

两层恢复都幂等；重启后组内每个服务的状态与历史保持一致、不丢失、不错乱，
随后可继续推进或按服务回滚。

### 10.6 与原单服务用法的兼容关系

- 原有 `/api/releases` 全部接口、请求体、错误码、状态机语义**保持不变**，
  旧调用方零改动；
- 单服务提交的 Release 只是 `groupId = null`，发布组成员的 Release 多了 `groupId`；
- 发布组成员同时可被单服务接口直接 `advance/rollback/get`，组级与单服务级操作
  走同一把按发布身份的锁，可安全交叉执行；
- 唯一的标识变化是 releaseId 增加了 serviceName 段（`rel-<serviceName>-...`），
  它只会让“不同服务相同输入”的身份不再冲突，调用方应始终使用响应中返回的
  `releaseId`，而不是自行拼接。
