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
| releaseId | `rel-<serviceName>-<commit 前 7 位>-<contentHash 前 12 位>`（**服务名参与身份**） |

因此：**相同输入 ⇒ 相同 releaseId / version / artifactSha256 / 路径**；任一输入变化则全部变化。
releaseId 必须携带服务名：单仓多服务场景下不同服务常打同一提交、同一内容，
若身份不含服务名，这些发布会互相撞 ID、被误判为同一条。

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

## 5. 发布组（单仓多服务批量编排）

本仓库是单仓多服务：几个服务经常打同一个提交，甚至源码内容一模一样。
**发布组**在此之上加一层批量编排：一次把一组服务（各自携带提交与内容）一起发起，
组内每个服务仍然走自己的发布身份与阶段进度，互相不影响。

```bash
# 提交发布组：每个成员就是一条完整的单服务发布输入
curl -s -X POST localhost:8080/api/release-groups -H 'Content-Type: application/json' -d '{
  "groupKey":"ticket-2001",
  "members":[
    {"serviceName":"billing-api","commit":"0123456789abcdef0123456789abcdef01234567","sourceContent":"hello release"},
    {"serviceName":"search-api","commit":"0123456789abcdef0123456789abcdef01234567","sourceContent":"hello release"},
    {"serviceName":"edge-api","commit":"0123456789abcdef0123456789abcdef01234567","sourceContent":"edge src","faultInjection":"fail:CANARY"}
  ]
}'

# 组级推进：每个可推进成员各自前进一个阶段（并行，互不影响）
curl -s -X POST localhost:8080/api/release-groups/<groupId>/advance

# 组级回滚：只回滚失败/推进中的成员，其余成员明确跳过
curl -s -X POST localhost:8080/api/release-groups/<groupId>/rollback

# 查询组（含派生组状态与每个成员的完整发布快照）
curl -s localhost:8080/api/release-groups/<groupId>
```

### 身份与隔离规则

- **成员身份**：成员的 releaseId 与单服务提交完全同源
  （`rel-<serviceName>-<commit7>-<hash12>`）。组内多个服务用**同一提交、同一内容**时，
  各自得到不同 releaseId、各自物化制品（制品清单含服务名）、各自写部署指针——
  必须各自成立，不会互相顶掉，也不会被当成同一条发布；
- **组身份**：`grp-<成员 releaseId 排序拼接后的 sha256 前 16 位>`，
  与成员顺序、`groupKey` 无关。**相同成员集合重复/并发提交幂等返回既有组**，
  不会产生第二个组或第二批发布；
- **同源去重**：同一 `(serviceName, commit, content)` 若此前已通过单服务接口提交，
  组提交会**收养**这条既有发布作为成员，而不是发起第二次部署；
- **故障隔离与逐成员结论**：组级推进/回滚只是“逐成员调用单服务编排 + 逐成员记录结论”。
  某成员在某阶段失败时，只有它自己以 `FAILED` 终态停下（保留失败阶段与原因），
  其余成员照常推进；**失败发生的那一轮，操作返回的 `outcomes` 里该成员立即就是失败结论**，
  调用方不需要再查一次组详情（判定语义见下文“逐成员结论判定语义”）；
- **按服务回滚**：组级回滚只对 `FAILED`/`IN_PROGRESS` 的成员生效，
  每个成员回滚到**它自己**的稳定版本登记册里的上一个稳定版本；
  健康成员记 `SKIPPED`（`ROLLBACK_NOT_ALLOWED`），部署指针不动；
- **组状态不持久化**，由成员状态实时推导（重启后天然一致）。
  失败终态优先：**只要存在 `FAILED` 成员，组状态即为 `FAILED`**，
  即使其他成员仍在推进——保证“某轮有成员失败”时组状态与该轮逐成员结论一致，
  失败不会被活跃成员掩盖。其余为：
  `PENDING`（全部待推进）、`IN_PROGRESS`（无失败且仍有成员在推进）、
  `ROLLING_BACK`、`COMPLETED`（全部完成）、
  `ROLLED_BACK`（全部已回滚）、`PARTIAL`（其余混合终态）；
- **校验**：组至少 1 个成员（否则 `INVALID_GROUP_REQUEST`）；
  同一服务在组内只能出现一次（否则 `GROUP_DUPLICATE_SERVICE`，HTTP 409）；
  成员入参与单服务提交同一套校验规则。

### 逐成员结论判定语义（`outcomes[]`，调用方以此为准，无需二次查询）

每次 `POST /api/release-groups/{id}/advance` 对组内**每个成员**都返回一条
`MemberOutcome`，字段为 `serviceName / releaseId / action / ok / code / message`。
组级推进以成员**落盘后的真实状态**为唯一依据，结论与成员真实结果严格一致：

| 本轮情形 | `action` | `ok` | `code` | `message` |
|----------|----------|------|--------|-----------|
| 阶段真正执行并通过（BUILD/STAGING/CANARY/PRODUCTION） | `ADVANCE` | `true` | 空 | 空 |
| **阶段本轮执行失败**（成员被置为 FAILED 终态） | `ADVANCE` | **`false`** | 原始失败码，如 `CANARY_HEALTH_CHECK_FAILED` | `stage <阶段> failed with <码>: <探针原始原因>`，失败发生当轮即返回 |
| 成员已在 FAILED 终态（含重启恢复出的失败） | `SKIPPED` | `false` | `RELEASE_FAILED` | 消息携带**原失败阶段与原失败码**（如 `RECOVERED_AFTER_INTERRUPT`），永不重跑 |
| 成员已 COMPLETED | `SKIPPED` | `false` | `RELEASE_ALREADY_COMPLETED` | 已完成说明，不重复部署 |
| 成员处于 ROLLING_BACK / ROLLED_BACK | `SKIPPED` | `false` | `RELEASE_ROLLING_BACK` / `RELEASE_ROLLED_BACK` | 对应说明 |
| 成员锁正被另一触发方持有（并发推进同一成员） | `SKIPPED` | `false` | `MEMBER_BUSY` | 由持锁方给真实结论，本轮不执行、不谎报 |

要点：

1. **失败在发生当轮就可见**：单服务编排的 `advance` 在阶段失败时是“落 FAILED 终态后
   正常返回”而非抛异常；组编排器以返回对象的状态为准，`FAILED` 即给出
   `ok=false` 的失败结论，绝不会因为没有异常而把它记成成功。
2. **终态失败后每轮只跳过且原因保留**：成员一旦 FAILED，之后每轮推进都记
   `SKIPPED/RELEASE_FAILED`，消息持续携带原失败阶段与失败码；
   不重新跑阶段、不重复部署、不会再次算作成功。
3. **其他成员不受牵连**：同一轮里健康成员照常各自推进一个阶段并给出自己的结论。
4. `SKIPPED` 的 `ok` 语义为 `false`：它表达“本轮没有执行新动作”，与真正的
   `ADVANCE` 成功区分；区分跳过原因看 `code`。调用方统计时：
   成功 = `ok=true`；失败 = `action=ADVANCE,ok=false`；跳过 = `action=SKIPPED`。

> 说明：单阶段失败发生时，组内其他成员可能仍未走完全部阶段，因此该轮组状态为
> `FAILED`（失败优先），与失败成员的逐成员结论一致；健康成员在后续轮次继续走到
> `COMPLETED`，组状态保持 `FAILED` 直到对失败成员执行按服务回滚（回滚后组可能为
> `PARTIAL`）。

### 并发与规模

- 组级推进/回滚在成员间并行执行，并行度由
  `release.orchestrator.group-parallelism`（默认 8）约束；
  **整轮扫描外加一把组锁**（与提交共用 `groups/<groupId>` 对应的
  进程内锁 + 文件锁，可重入）：同一组的推进/回滚扫描彼此串行、推进与回滚互斥，
  拿不到组锁立即返回 `GROUP_BUSY`（HTTP 409），调用方可重试。
  锁粒度在成员层仍是单个 releaseId，不同服务互不阻塞，
  几十个服务一批提交时整体耗时随服务数近似线性增长；
- 并发提交同一组：只建一个组（其余幂等返回同组或 `GROUP_BUSY`）；
  并发对同组推进：组锁串行化各轮扫描，成员锁保证每个成员每个阶段恰好执行一次，
  失败成员的失败结论在“失败当轮”只产生一次，之后所有并发扫描都稳定记
  `SKIPPED/RELEASE_FAILED`，不多发、不跳阶段、不把失败覆盖成成功；
- 中断恢复：组成员就是普通发布，启动恢复扫描对组内成员逐一生效，
  只把被中断的成员调和为 `FAILED(RECOVERED_AFTER_INTERRUPT)`，同伴原样保留，
  重启后组可继续推进；恢复出的失败成员在重启后第一轮推进即记
  `SKIPPED/RELEASE_FAILED` 并携带 `RECOVERED_AFTER_INTERRUPT`，不会被重跑。

### 与单服务用法的兼容关系

- 单服务接口（`/api/releases/**`）行为完全不变；组成员就是一条普通发布，
  可以用单服务接口查询、（在允许状态下）单独回滚；
- 同一输入无论从单服务接口还是组接口进入，都收敛到同一条发布身份，
  两条路径互为幂等，不会重复部署；
- 唯一的行为收紧：releaseId 现在携带服务名（见第 2 节），
  这使“不同服务、相同提交与内容”从过去的撞 ID 拒绝变为各自合法成立。

---

## 6. 一致性机制（并发 / 重复 / 交叉）

- 每次 `submit / advance / rollback / recover` 都在临界区内执行：
  **按 releaseId 获取进程内 `ReentrantLock` + JVM 文件锁（`locks/<id>.lock`，可重入）
  → 从磁盘加载最新状态 → 判定 → 原子写回状态 + 仅追加事件 → 释放锁**；
- **组级推进/回滚额外持有整组的组锁**（同一把锁也保护组提交，可重入），
  使同一组的批量扫描彼此串行、推进与回滚互斥；锁内仍逐成员并行、各持各的成员锁，
  因此组内不牺牲并行度，跨组也互不阻塞；拿不到组锁返回 `GROUP_BUSY`；
- 锁等待超时返回 `RELEASE_BUSY`，避免调用方无限挂起；组扫描中某成员锁被他人持有时，
  该成员本轮记 `SKIPPED/MEMBER_BUSY`（不报错、不谎报结论），由持锁方负责真实结论；
- 状态文件 `releases/<releaseId>.json` 采用临时文件 + 原子替换；
- 事件追加在文件锁保护下进行并递增全局 `seq`；
- 因此并发提交只会有一个赢家（其余 `DUPLICATE_SUBMISSION`/`RELEASE_BUSY`），
  并发推进下每个阶段 `STAGE_STARTED` 与部署恰好一次，不重复部署、不跳阶段、状态不错乱；
  并发组推进中，某成员的失败结论只在失败当轮出现一次，此后永远是带原原因的跳过，
  失败不会被任何并发或重启后的推进覆盖成成功。

---

## 7. 本地数据目录布局（默认 `target/release-data`）

```
<dataDir>/
├── artifacts/<serviceName>/<artifactSha256>.bin   # 内容寻址制品（原子写入）
├── releases/<releaseId>.json                      # 每个发布的完整状态（原子替换）
├── groups/<groupId>.json                          # 每个发布组的成员名册（原子替换，状态实时推导）
├── journal/events.log                             # 仅追加事件日志（单行 JSON，全局有序）
├── journal/events.lock                            # 事件日志跨进程锁
├── locks/<releaseId>.lock                         # 发布操作跨进程锁
├── registry/<serviceName>.txt / .lock             # 稳定版本登记册及锁
└── deployments/<serviceName>/current.json         # 当前生效部署指针（原子替换）
```

状态文件损坏时抛 `STATE_CORRUPT` 并保留原文件，**绝不静默重建**。

---

## 8. 关键配置项（`src/main/resources/application.properties`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `release.orchestrator.data-dir` | `target/release-data` | 本地状态与制品根目录；也可用环境变量 `RELEASE_DATA_DIR` 覆盖 |
| `release.orchestrator.lock-timeout-millis` | `5000` | 同一发布并发操作的锁等待毫秒，超时返回 `RELEASE_BUSY` |
| `release.orchestrator.recover-on-startup` | `true` | 启动时是否自动调和中断状态 |
| `release.orchestrator.group-parallelism` | `8` | 发布组批量推进/回滚时的成员级并行度上限 |

---

## 9. HTTP 接口与错误码

| 方法与路径 | 说明 |
|------------|------|
| `POST /api/releases` | 提交发布（body 为 ReleaseRequest） |
| `POST /api/releases/{id}/advance` | 推进下一个阶段 |
| `POST /api/releases/{id}/rollback` | 回滚到上一个稳定版本 |
| `GET  /api/releases/{id}` | 查询单个发布（含各阶段状态与失败原因） |
| `GET  /api/releases` | 列出全部发布 |
| `POST /api/release-groups` | 提交发布组（body 为 ReleaseGroupRequest；相同成员集合幂等返回既有组） |
| `POST /api/release-groups/{id}/advance` | 组级推进：每个可推进成员各自前进一个阶段（逐成员结论） |
| `POST /api/release-groups/{id}/rollback` | 组级回滚：只回滚失败/推进中的成员，其余跳过 |
| `GET  /api/release-groups/{id}` | 查询组（派生组状态 + 每个成员的完整发布快照） |
| `GET  /api/release-groups` | 列出全部发布组 |

错误响应统一为 `{ timestamp, releaseId, code, message }`。HTTP 状态：
`404`（`RELEASE_NOT_FOUND` / `GROUP_NOT_FOUND`）、
`409`（并发冲突 / 非法流转 / 重复提交 / 组内重复服务）、
`400`（入参或制品校验等其余业务错误）。`code` 是稳定可区分的错误码。

---

## 10. 本地验证方法（离线、确定性）

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
| `ReleaseGroupSubmitTest` | 组内跨服务相同提交+相同内容各自成立（独立身份/制品/指针）、组重复提交幂等、收养单服务既有发布、组入参校验码 |
| `ReleaseGroupFailureIsolationTest` | 组内单服务 CANARY 失败只停自己（可解释终态+失败原因），同伴照常完成；按服务回滚到各自稳定版、健康成员跳过且指针不动；组成员可用单服务接口查询/回滚；**失败发生当轮的逐成员结论即 `ADVANCE/ok=false/CANARY_HEALTH_CHECK_FAILED`（无需二次查询），之后每轮稳定 `SKIPPED/RELEASE_FAILED` 且保留原因、CANARY 恰好执行一次** |
| `ReleaseGroupConcurrencyTest` | 12 线程并发提交同组只建一组；30 服务一批并行推进（每成员每阶段恰好一次、无串台、逐服务指针）；6 线程并发扫组仍保持阶段恰好一次；**8 线程并发推进含失败成员的组：失败结论当轮恰好一次、成功只出现在失败前的阶段、失败后所有扫描均跳过并保留原因、失败不被覆盖、同伴四阶段各一次** |
| `ReleaseGroupRecoveryTest` | 组内成员 CANARY 执行中“被 kill”，全新 Spring 上下文重启：仅该成员调和为 FAILED(RECOVERED_AFTER_INTERRUPT)，同伴原样保留并继续走完，历史事件完整；**重启后第一轮推进该成员即 `SKIPPED/RELEASE_FAILED` 且带回 `RECOVERED_AFTER_INTERRUPT`，重启后不产生任何新的 STAGE_STARTED** |
| `ReleaseGroupControllerWebTest` | 组 HTTP 端到端：提交-推进-查询全旅程、GROUP_NOT_FOUND/GROUP_DUPLICATE_SERVICE/INVALID_GROUP_REQUEST 映射 404/409/400 |

### 手动复现一次“失败 → 重启恢复 → 回滚”

1. 用 `faultInjection:"fail:CANARY"` 提交，连续 `advance` 至 CANARY 失败（`FAILED`）；
2. 直接把 `releases/<id>.json` 中 CANARY 状态改为 `RUNNING` 并把 `status` 改为
   `IN_PROGRESS`（模拟阶段执行中进程被 kill），重新启动应用，观察启动日志中的
   `RECOVERED_AFTER_INTERRUPT` 调和；
3. 先完整发布一个健康版本，再制造一个失败发布，调用 `/rollback`，
   检查 `deployments/<serviceName>/current.json` 是否切回上一个稳定版本。

### 手动复现一次“发布组：组内失败隔离 → 按服务回滚”

1. 先让 `svc-a` 完整发布一个健康版本（单服务接口提交 + 4 次 advance），成为它的稳定版本；
2. 提交发布组：`svc-a` 携带新内容且 `faultInjection:"fail:CANARY"`，
   `svc-b`、`svc-c` 健康（可与 svc-a 用同一 commit、同一内容，验证相同输入各自成立）；
3. 连续调用 `POST /api/release-groups/<groupId>/advance` 4 次，逐轮检查响应中的
   `outcomes[]`：
   - 第 1、2 轮（BUILD/STAGING）：三个成员均为 `{"action":"ADVANCE","ok":true}`；
   - **第 3 轮（CANARY）**：`svc-a` 立即返回
     `{"action":"ADVANCE","ok":false,"code":"CANARY_HEALTH_CHECK_FAILED",
     "message":"stage CANARY failed with CANARY_HEALTH_CHECK_FAILED: ..."}`
     ——失败在发生当轮就明确标出，无需再调 `GET` 查组详情；同轮 `svc-b/svc-c`
     仍是 `ok=true`；响应里的组 `status` 为 `FAILED`；
   - 第 4 轮及以后：`svc-a` 恒为
     `{"action":"SKIPPED","ok":false,"code":"RELEASE_FAILED",
     "message":"... terminal FAILED state at stage CANARY (CANARY_HEALTH_CHECK_FAILED) ..."}`，
     `svc-b/svc-c` 走完后变为 `SKIPPED/RELEASE_ALREADY_COMPLETED`；
   可用 `jq '.outcomes[] | {serviceName,action,ok,code}'` 只看结论；
4. 调用 `POST /api/release-groups/<groupId>/rollback`：
   只有 `svc-a` 回滚到自己的上一个稳定版本（`deployments/svc-a/current.json` 切回），
   `svc-b`、`svc-c` 记 `SKIPPED` 且部署指针不动，组状态变为 `PARTIAL`；
5. 并发演练：用 `ab`/`xargs -P` 或小段 shell 同时发起多个
   `POST /advance`，观察部分调用拿到 HTTP 409 `GROUP_BUSY`（可重试），
   成功执行的各轮结论序列与第 3 步完全一致，且
   `journal/events.log` 中失败成员的 `STAGE_STARTED stage=CANARY` 只有一条；
6. 中断恢复演练：在任一组成员某阶段 `RUNNING` 落盘后杀掉进程（或手工把
   `releases/<id>.json` 中该阶段改为 `RUNNING`），重启应用，
   观察仅该成员被调和为 `FAILED(RECOVERED_AFTER_INTERRUPT)`，其余成员与组记录原样保留；
   重启后第一次 `POST /advance` 的响应中该成员即为
   `SKIPPED/RELEASE_FAILED`（消息含 `RECOVERED_AFTER_INTERRUPT`），不会重跑该阶段。
