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
| releaseId | `rel-<commit 前 7 位>-<contentHash 前 12 位>` |

因此：**相同输入 ⇒ 相同 releaseId / version / artifactSha256 / 路径**；任一输入变化则全部变化。

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
├── releases/<releaseId>.json                      # 每个发布的完整状态（原子替换）
├── journal/events.log                             # 仅追加事件日志（单行 JSON，全局有序）
├── journal/events.lock                            # 事件日志跨进程锁
├── locks/<releaseId>.lock                         # 发布操作跨进程锁
├── registry/<serviceName>.txt / .lock             # 稳定版本登记册及锁
└── deployments/<serviceName>/current.json         # 当前生效部署指针（原子替换）
```

状态文件损坏时抛 `STATE_CORRUPT` 并保留原文件，**绝不静默重建**。

---

## 7. 关键配置项（`src/main/resources/application.properties`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `release.orchestrator.data-dir` | `target/release-data` | 本地状态与制品根目录；也可用环境变量 `RELEASE_DATA_DIR` 覆盖 |
| `release.orchestrator.lock-timeout-millis` | `5000` | 同一发布并发操作的锁等待毫秒，超时返回 `RELEASE_BUSY` |
| `release.orchestrator.recover-on-startup` | `true` | 启动时是否自动调和中断状态 |

---

## 8. HTTP 接口与错误码

| 方法与路径 | 说明 |
|------------|------|
| `POST /api/releases` | 提交发布（body 为 ReleaseRequest） |
| `POST /api/releases/{id}/advance` | 推进下一个阶段 |
| `POST /api/releases/{id}/rollback` | 回滚到上一个稳定版本 |
| `GET  /api/releases/{id}` | 查询单个发布（含各阶段状态与失败原因） |
| `GET  /api/releases` | 列出全部发布 |

错误响应统一为 `{ timestamp, releaseId, code, message }`。HTTP 状态：
`404`（`RELEASE_NOT_FOUND`）、`409`（并发冲突 / 非法流转 / 重复提交）、
`400`（入参或制品校验等其余业务错误）。`code` 是稳定可区分的错误码。

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

### 手动复现一次“失败 → 重启恢复 → 回滚”

1. 用 `faultInjection:"fail:CANARY"` 提交，连续 `advance` 至 CANARY 失败（`FAILED`）；
2. 直接把 `releases/<id>.json` 中 CANARY 状态改为 `RUNNING` 并把 `status` 改为
   `IN_PROGRESS`（模拟阶段执行中进程被 kill），重新启动应用，观察启动日志中的
   `RECOVERED_AFTER_INTERRUPT` 调和；
3. 先完整发布一个健康版本，再制造一个失败发布，调用 `/rollback`，
   检查 `deployments/<serviceName>/current.json` 是否切回上一个稳定版本。
