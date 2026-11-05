# WorkflowProvider SPI v2.4（冻结基线）

- 状态：accepted / record 形状冻结
- 日期：2026-11-04
- 契约包：`core/src/main/java/cn/byteforce/seedj/workflow/spi`（零外部依赖）
- 参照实现：`workflow-infra-memory`
- 关联：`docs/spi/README.md`、`ADR-0002-infra-spi-constitution.md`

## 1. 定位

`workflow/spi` 是跨模块、跨团队、跨发布周期的二进制边界。契约不只写方法签名，还必须写清：
语义、生命周期、失败模型、幂等边界、版本兼容、事件顺序、可观测约定。

v2.4 是 v2.3 发布前的最终 polish 版本。自本版本起，以下 record/enum 的**字段形状冻结**：

`WorkflowDefinition`、`StepDefinition`、`HumanTaskSpec`、`HumanTask`、`TaskAssignment`、
`WorkflowAction` 族、`WorkflowCapabilities`。

后续新增能力只允许：

1. 接口新增 `default` 方法；
2. `WorkflowCapabilities` 新增字段（旧 Provider 返回 `false`）；
3. `WorkflowAction` sealed permits 追加新类型（旧 Provider 抛 `WorkflowStateConflictException`）；
4. 新增 `V2` record + `default` 适配方法。

任何破坏性变更必须开启 v3 基线。

## 2. 公开表面

| 组 | 类型 |
|---|---|
| 标识 | `WorkflowDefinitionId`、`WorkflowInstanceId`、`WorkflowDefinitionVersion`、`TaskId` |
| 参与者 | `ParticipantType`、`ParticipantSpec`、`ParticipantResolutionContext` |
| 会签 | `SignCompletion`（sealed）、`AllApprove`、`AnyApprove`、`AtLeastCount`、`AtLeastPercentage` |
| 驳回 | `RejectCompletion`（sealed）、`AnyRejectTerminates`、`RecordAndContinue`、`AtLeastRejects` |
| 定义 | `WorkflowDefinition`、`StepDefinition`、`StepKind`、`HumanTaskSpec`、`RetryPolicy` |
| 实例 | `WorkflowInstance`、`WorkflowStatus`、`WorkflowFailure` |
| 任务 | `HumanTask`、`HumanTaskStatus`、`HumanTaskKind`、`TaskAssignment`、`AssignmentStatus`、`ApproveRecord`、`ActionDecision` |
| 动作 | `WorkflowAction`（sealed）、`SignalAction`、`ApproveAction`、`RejectAction`、`AddAssigneeAction`、`AddAssigneePosition`、`RemoveAssigneeAction`、`TransferAction`、`DelegateAction`、`WithdrawAction`、`WithdrawInstanceAction`、`UrgeAction`、`CcAction`、`ResumeWorkflowAction` |
| 请求 | `StartWorkflowRequest`、`ActionRequest`、`CancelWorkflowRequest`、`TerminateWorkflowRequest`、`WorkflowSearchQuery`、`TaskQuery` |
| 入口 | `WorkflowProvider`、`WorkflowCapabilities` |
| 异常 | `WorkflowException` + 13 个子类 |
| 历史 | `WorkflowHistoryEvent` |

一类型一文件，构造期校验，禁止 `Ports`/`Models` 容器类。

## 3. 核心语义（normative）

### 3.1 定义、实例、幂等

- 定义是模板，实例是运行态；`start` 时固定定义版本，运行中不切换。
- `start` 按 `instanceId` 幂等：重复调用返回已有实例；定义版本不同抛
  `WorkflowVersionConflictException`。
- `act` 按 `actionId` 全局幂等：重复调用返回当前实例状态，不重复驱动。
- 每次状态转换必须先持久化再返回；调用方不直接写状态。
- 失败用 `WorkflowFailure` 结构化表达：`errorType`、`errorMessage`、`retryable`、
  `failedStep`、`details`。

### 3.2 时钟

- 租约、超时、重试、`dueAt`、`delegateFor` 全部使用 Provider 时钟。
- `WorkflowProvider.now()` 暴露 Provider 时钟；`capabilities().supportsProviderClock == true`
  的实现必须覆盖。
- `action.occurredAt` 仅作业务时间；`delegateFor` 是相对时长，到期时间 = `now() + delegateFor`。
- 状态变更时间由 Provider 填充，调用方不得传入。

### 3.3 会签计数（§1.5.1）

- 分母：`status ∈ {PENDING, COMPLETED, SUSPENDED}` 的 assignment 数。
  不含 `CANCELLED`、`TRANSFERRED`、`DELEGATED`。
- 分子：`COMPLETED` 且 `record.decision == APPROVE`。
- `REJECT` 记录计入分母、不计入分子。
- `AllApprove`：分子 == 分母且无 REJECT。
- `AnyApprove`：分子 >= 1。
- `AtLeastCount(n)`：分子 >= n。
- `AtLeastPercentage(p)`：分子 >= `ceil(p × 分母)`。
- 超时 `CANCELLED` 不计入分母；已 APPROVE 的计数保留。

### 3.4 死锁终态（§1.5.1.1）

触发条件：任务无活跃态（无 `PENDING`/`SUSPENDED`；无待派生 AFTER；无代理 PENDING）
且 `SignCompletion` 不满足。

| 场景 | 处置 |
|---|---|
| 存在 REJECT 记录且规则不满足 | 任务 `REJECTED(reason=SIGN_COMPLETION_UNSATISFIABLE)`；回退目标 = 最后一个 `RejectAction.targetStep`（`occurredAt` 排序，同刻按 `actionId` 字典序）；按 §1.4 级联；写 `TASK_REJECTED`、`INSTANCE_ROLLED_BACK` |
| `AtLeastRejects(n)` 已达 n 次 REJECT | 任务 `REJECTED(reason=REJECT_THRESHOLD_REACHED)`；回退目标 = 第 n 次触发终止的 `RejectAction.targetStep` |
| 无 REJECT 记录且规则不满足（仅可能由超时 `CANCELLED` 导致） | 实例 `FAILED(errorType=SIGN_COMPLETION_UNSATISFIABLE)`；任务 `CANCELLED`；`TASK_CANCELLED` → `INSTANCE_FAILED` |

关键规则：任务终态后取消所有待派生记录、委托到期调度、SUSPENDED 计时。
`DELEGATED` 在代理人已处理时是历史记录，不参与死锁判定；其活跃性由代理人 `PENDING` 表达。

### 3.5 驳回 / 撤回回退（§1.4）

以 `RejectAction(targetStep=B)` 或 `WithdrawAction(targetStep=B)`、当前步骤 `C`、`B < C` 为例：

| 对象 | 处理 |
|---|---|
| 当前任务 | `REJECTED` / `WITHDRAWN`，assignments 保留历史，从 `listTasks(PENDING)` 视图消失 |
| B 到 C 之间的活跃任务 | 任务 `CANCELLED`；assignment `CANCELLED(UPSTREAM_ROLLBACK)`；写 `TASK_ASSIGNMENT_AUTO_CANCELLED` + `TASK_CANCELLED` |
| B 到 C 之间的 `COMPLETED` 任务 | 状态不变；记录不参与后续规则计算 |
| 委托 / 转办中的 assignment | `DELEGATED` 一并取消并取消到期调度；`TRANSFERRED` 保留 |
| 待派生 AFTER 记录 | 丢弃，写 `TASK_AFTER_SPAWN_DISCARDED(reason=UPSTREAM_ROLLBACK)` |
| 目标步骤 B | 创建全新 `HumanTask`（新 `TaskId`），assignments 重新初始化 `PENDING`，规格按定义重新解析 |
| 会签规则 | 按新任务规格重新计算，不复用历史 |
| 实例 | `currentStep=B`；`pendingTaskIds` 只含 B 的新任务；写 `INSTANCE_ROLLED_BACK` |

不变量：

- 同一 `(instanceId, stepName)` 任一时刻最多一个含 `PENDING` assignment 的 `HumanTask`。
- `BEFORE_ADDED` / `AFTER_ADDED` 复用原 `stepName`，通过 `predecessorTaskId` /
  `successorTaskId` 串链。
- `pendingTaskIds` 只包含含 `PENDING` assignment 的任务；`SUSPENDED` 不出现在
  `listTasks(pendingFor)`。

### 3.6 前加签 BEFORE（§1.5.2）

- 原任务 `PENDING`；位置在 `allowedAddPositions`；Provider 支持 BEFORE。
- 原任务所有 `PENDING` assignment → `SUSPENDED(BEFORE_ADDED_SUSPENDED)`；任务仍 `PENDING`；
  从 `pendingTaskIds` / `pendingFor` 消失；`dueAt` 暂停计时。
- 派生 `BEFORE_ADDED` 任务：复用 `stepName`，assignees 替换为新增参与者，
  `predecessorTaskId=原任务`，`successorTaskId=null`，`spawnedByActionId=actionId`；
  原任务 `successorTaskId=新任务`。
- `AtLeastCount(n)` 默认取 `min(n, newSize)`；`metadata.allowAddAssigneeReducesThreshold=false`
  且 `n > newSize` 时抛 `WorkflowStateConflictException`。
- 派生任务完成：原任务 `SUSPENDED` → `PENDING`（`resolvedReason=null`，`dueAt` 顺延），
  回到 `pendingTaskIds`；写 `TASK_ASSIGNMENT_RESUMED`。
- 派生任务驳回：按 §1.4 回退，原任务取消。
- `currentStep` 不变。

### 3.7 后加签 AFTER（§1.5.3）

- 原任务 `PENDING` 或 `COMPLETED` 可发起。
- 原任务 `PENDING`：只持久化待派生记录，不创建任务。
- 原任务 `COMPLETED`：立即派生 `AFTER_ADDED` 任务，插入原任务与下一步骤之间。
- 原任务进入非 `COMPLETED` 终态（`REJECTED` / `WITHDRAWN` / `CANCELLED`）：
  丢弃待派生记录，写 `TASK_AFTER_SPAWN_DISCARDED`。
- AFTER × BEFORE：BEFORE 挂起期间待派生记录保持；BEFORE 完成恢复后仍有效；
  BEFORE 驳回导致原任务取消时按 `UPSTREAM_ROLLBACK` 丢弃。
- `AFTER_ADDED.signCompletion` 默认 `AnyApprove`；可用
  `metadata.afterAddSignCompletion` 覆盖：
  `AllApprove` / `AtLeastCount:<n>` / `AtLeastPercentage:<p>`。
- `assignees` 替换为新增参与者；`rejectCompletion` / `rejectTargets` 沿用。
- 完成后按 `WorkflowDefinition.steps` 列表顺序取下一个步骤；最后一步则实例 `COMPLETED`。
  AFTER_ADDED 不改变原步骤出边拓扑。
- 派生任务存在时 `currentStep` 仍为原 `stepName`；`pendingTaskIds` 只含派生任务。

### 3.8 委托 × 会签（§1.5.4）

- 原 assignment `PENDING → DELEGATED`；新增代理人 assignment `PENDING`。
- `onBehalfOf` 不在 assignment 层存储，只在代理人处理时写入 `ApproveRecord.onBehalfOf`。
- 分母：`DELEGATED` 不计入；代理人 `PENDING`/`COMPLETED` 计入。
- 代理人审批：代理人 assignment `COMPLETED`；`record.assignee=代理人`、
  `record.onBehalfOf=原审批人`；原 `DELEGATED` 保持。
- 到期：代理人仍 `PENDING` → 代理人 `CANCELLED(DELEGATION_EXPIRED)`，原 `DELEGATED → PENDING`；
  代理人已 `COMPLETED` → 原 `DELEGATED` 保持，写 `DELEGATION_EXPIRED_NOOP`。
- 不允许嵌套委托。
- 任务终态：`PENDING`/`SUSPENDED`/`DELEGATED` → `CANCELLED` 并取消到期调度；
  `TRANSFERRED` 保持；`COMPLETED` 保持。`TRANSFERRED` 派生的活跃 assignment 一并取消。

### 3.9 撤回（§1.5.6 / §1.5.8）

- 任务级 `WithdrawAction`：`operator` 必须是该任务某 `COMPLETED(APPROVE)` record 的 `assignee`；
  下游未完成；不能撤回 REJECT 记录。触发撤回的 assignment 保持 `COMPLETED`，
  撤回只体现在 `TASK_WITHDRAWN` 历史事件。
- 实例级 `WithdrawInstanceAction`：仅 `startedBy` 可发起；所有活跃 assignment
  `CANCELLED(WITHDRAWN)`；`COMPLETED` 保持；实例进入终态。
- 并发冲突：先持久化者赢，后到者抛 `WorkflowStateConflictException`；
  撤回校验通过但持久化前下游完成 → 同样抛冲突。
- `WITHDRAWN` 任务的 `approveRecords()` 仅作历史审计，不具推进效力。
- `ActionDecision` 不新增 `WITHDRAW`。

### 3.10 HUMAN_TASK 超时 / WAITING / Resume（§1.5.9）

| 超时 | 任务 | 实例 | 事件 |
|---|---|---|---|
| `dueIn` | `CANCELLED(TASK_OVERDUE)`；活跃 assignment 取消；COMPLETED 保留 | `metadata.timeoutPolicy=WAITING` → `WAITING`；否则 `FAILED` | `TASK_ASSIGNMENT_AUTO_CANCELLED` → `TASK_OVERDUE` → `INSTANCE_FAILED`/`INSTANCE_WAITING` |
| `timeout` | `CANCELLED(STEP_TIMEOUT)`；所有活跃 assignment 取消 | `FAILED(STEP_TIMEOUT)` | `TASK_ASSIGNMENT_AUTO_CANCELLED` → `TASK_CANCELLED` → `INSTANCE_FAILED` |
| WAITING 后 `timeout` 继续 | — | `FAILED` | `INSTANCE_FAILED` |

- `HumanTaskSpec.dueIn <= StepDefinition.timeout`，构造时校验，否则 `IllegalArgumentException`。
- `ResumeWorkflowAction`：仅 `WAITING`；管理员或 `startedBy`；重建当前步骤 `HumanTask`
  （新 `TaskId`，`ORIGINAL`，assignments 重新解析）；`dueAt = now() + newDueIn`；
  实例 `WAITING → RUNNING`；写 `INSTANCE_RESUMED → TASK_CREATED`。
- 剩余 `timeout` 预算不足（已消耗 + `newDueIn > timeout`）→ `WorkflowStateConflictException`。
- `WAITING` 实例只接受 `ResumeWorkflowAction`、`CcAction`、`WithdrawInstanceAction`、
  `cancel`、`terminate`。

### 3.11 抄送 / 催办（§1.5.11）

- `CcAction.operator` 必须显式声明且非空白；任务级抄送要求 `allowCc`；
  实例级只要求 `capabilities().supportsCc`；写 `TASK_CC`，不改状态机。
- `UrgeAction.operator` 必须是参与者或管理员；Provider 做存在性校验。
- 终态任务催办 → `HumanTaskStateConflictException`，不写事件。
- 前加签期间原任务（assignment `SUSPENDED`）允许催办，`attributes.targetStatus=SUSPENDED`。

### 3.12 活跃态 / 终态（§1.5.12）

| 状态 | 分类 |
|---|---|
| `PENDING` | 活跃；计入分母 |
| `SUSPENDED` | 活跃；计入分母；不参与 `pendingFor` |
| `DELEGATED` | 活跃；不计入分母；到期可能恢复 `PENDING` |
| `TRANSFERRED` | 准活跃；本身不计入分母、不参与死锁 |
| `COMPLETED` | 终态；计入分母 |
| `CANCELLED` | 终态；不计入分母 |

### 3.13 事件顺序（§1.5.10）

| 场景 | 顺序 |
|---|---|
| 规则满足 | 触发者 COMPLETED → 其余活跃 assignment CANCELLED(RULE_SATISFIED) → 任务 COMPLETED → `TASK_COMPLETED` |
| 任一驳回 | 触发者 COMPLETED(REJECT) → 其余 CANCELLED(REJECTED_TERMINATED) → 任务 REJECTED → `TASK_REJECTED` → `INSTANCE_ROLLED_BACK` |
| 死锁 | 触发者 COMPLETED(REJECT) → 其余 CANCELLED(SIGN_COMPLETION_UNSATISFIABLE) → 任务 REJECTED → `TASK_REJECTED`（含 `reason`、`rejectActionIds`）→ `INSTANCE_ROLLED_BACK` |
| 驳回阈值 | 第 n 次 REJECT → 其余 CANCELLED(REJECT_THRESHOLD_REACHED) → 任务 REJECTED → `TASK_REJECTED` → `INSTANCE_ROLLED_BACK` |
| 任务级撤回 | 触发者保持 COMPLETED(APPROVE) → 其余 CANCELLED(WITHDRAWN) → 任务 WITHDRAWN → `TASK_WITHDRAWN` → 中间任务取消（`TASK_ASSIGNMENT_AUTO_CANCELLED` + `TASK_CANCELLED`）→ 丢弃待派生 → `INSTANCE_ROLLED_BACK` |
| 实例级撤回 | 所有活跃 assignment CANCELLED(WITHDRAWN) → 当前任务 WITHDRAWN → `TASK_WITHDRAWN` → 中间任务取消 → 丢弃待派生 → `INSTANCE_WITHDRAWN` → `INSTANCE_ROLLED_BACK` |
| BEFORE 加签 | 原 assignment SUSPENDED → `TASK_ASSIGNMENT_SUSPENDED` → 派生任务 → `TASK_ADD_ASSIGNEE_BEFORE` |
| BEFORE 完成恢复 | 派生任务 COMPLETED → `TASK_COMPLETED` → 原 assignment PENDING（`dueAt` 顺延）→ `TASK_ASSIGNMENT_RESUMED` |
| AFTER 立即派生 | 派生 `AFTER_ADDED` → `TASK_ADD_ASSIGNEE_AFTER` |
| AFTER 延迟派生 | 原任务 COMPLETED → `TASK_COMPLETED` → 派生 `AFTER_ADDED` → `TASK_ADD_ASSIGNEE_AFTER` |
| AFTER 丢弃 | `TASK_AFTER_SPAWN_DISCARDED` |
| 委托发起 | 原 PENDING → DELEGATED → 代理人 PENDING → `TASK_DELEGATED` |
| 委托到期（未处理） | 代理人 PENDING → CANCELLED(DELEGATION_EXPIRED) → 原 DELEGATED → PENDING → `DELEGATION_EXPIRED` |
| 委托到期（已处理） | 原 DELEGATED 保持 → `DELEGATION_EXPIRED_NOOP` |
| `dueIn` 超时 | 活跃 assignment CANCELLED(TASK_OVERDUE) → `TASK_ASSIGNMENT_AUTO_CANCELLED` → 任务 CANCELLED → `TASK_OVERDUE` → `INSTANCE_FAILED`/`INSTANCE_WAITING` |
| `timeout` 超时 | 活跃 assignment CANCELLED(STEP_TIMEOUT) → `TASK_ASSIGNMENT_AUTO_CANCELLED` → 任务 CANCELLED → `TASK_CANCELLED` → `INSTANCE_FAILED` |
| Resume | `INSTANCE_RESUMED` → `TASK_CREATED` |
| 其它 | 加签 CURRENT `TASK_ASSIGNEE_ADDED`；减签 `TASK_ASSIGNEE_REMOVED`；转办 `TASK_TRANSFERRED`；抄送 `TASK_CC`；催办 `TASK_URGED` |

## 4. v2.4 修复清单

1. `HumanTaskSpec` 删除静默修正与死代码：`allowAddAssignee=true` +
   空 `allowedAddPositions` → `IllegalArgumentException`。
2. `WorkflowCapabilities` 新增 `supportsProviderClock`、`supportsResumeWorkflow`（21 项）。
3. 新增 §1.5.12 活跃态/终态定义，死锁检测不再误判 `DELEGATED`/`TRANSFERRED`。
4. `TaskAssignment` 明确 `TRANSFERRED` 不携带 `record`。
5. `onBehalfOf` 仅在 `ApproveRecord`，assignment 层不重复存储。
6. 撤回事件补中间任务 `TASK_ASSIGNMENT_AUTO_CANCELLED` + `TASK_CANCELLED`。
7. `UrgeAction` 终态抛 `HumanTaskStateConflictException`；`SUSPENDED` 允许催办。
8. 新增 `ResumeWorkflowAction`，补齐 `WAITING` 人工干预入口。
9. §1.5.1.1 简化为三行决策表。
10. `AFTER_ADDED` 默认 `AnyApprove`，可被 metadata 覆盖。

## 5. 实现解释与已知偏差

契约没有定义、由本仓库实现自行解释的点：

| 点 | 本仓库决策 | 原因 |
|---|---|---|
| SPI 发现 | 不实现草案 §6 `ProviderFactory`；用 `@AutoConfiguration` + bean 名 `memory` + `seedj.workflow.provider` | `ADR-0002` 已删除 `ProviderFactory`/`ServiceLoader` 反射 |
| 实例级撤回终态 | `WorkflowStatus.CANCELLED`，并写 `INSTANCE_WITHDRAWN` + `INSTANCE_ROLLED_BACK` | `WorkflowStatus` 没有 `WITHDRAWN` 值；v2.4 未新增状态 |
| `ACTIVITY` / `COMPENSATION` | 内存实现立即完成；`cancel(compensate=true)` 只做 `COMPENSATING → CANCELLED` 与事件 | `core` 没有外部执行器契约，不能凭空定义执行语义 |
| `supportsCompensation` | 内存实现按 §7.1 声明 `true` | 与上一条一起，接入真实执行器时必须以新 port 或能力降级修正 |
| `ResumeWorkflowAction.operator` | `startedBy` 或字面量 `admin` | `core` 无认证/角色模型；管理员身份由调用方保证 |
| 任务级撤回 `targetStep` | 必须在当前步骤之前或同步骤；`currentStep` 之后已完成的步骤阻止撤回 | §1.5.6 "下游已 COMPLETED" |
| 回退时的已完成中间步骤 | 保留历史，允许回退（与 §1.4 一致） | 下游只看当前任务之后 |
| AFTER_ADDED 在已推进步骤上追加 | 允许并行追加，不把 `currentStep` 回退 | 避免破坏已运行的后续步骤 |
| `metadata.afterAddSignCompletion` | 字符串：`AllApprove`、`AtLeastCount:<n>`、`AtLeastPercentage:<p>` | metadata 是 `Map<String,String>` |
| 搜索游标 | 内存实现使用数字 offset 字符串 | 无持久化存储；不构成契约承诺 |
| 代理人与普通 assignee 同值 | 优先按委托代理处理 | 避免 `onBehalfOf` 丢失；极端歧义场景由定义者避免 |
| 错误模型 | 契约使用 v2.4 的 `WorkflowException` 族 | 与 `docs/spi/README` R5 的 `InfrastructureException` 尚未收敛；需在 workflow 能力评审时决策 |

尚未实现（按落地顺序后续接入）：

- `workflow-infra-jdbc`（PostgreSQL 表、乐观锁、数据库时钟）
- `workflow-infra-temporal`
- `workflow-infra-camunda` / `flowable`
- 指标/Micrometer 适配（§10）
- `starter` 中的 Spring 桥接选择（当前由实现模块自带自动配置）

## 6. 版本兼容

- 接口新增方法必须 `default`。
- record 字段变更必须新建 `XxxV2` + `default` 适配。
- 定义版本固定；实例运行中不升级。
- `providerName`/bean 名全局唯一，重名 fail-fast。
- v2.4 起 record 字段形状冻结；破坏性变更开启 v3。

## 7. 验收

### 7.1 测试

```bash
mvn -o -pl workflow-infra-memory -am test
```

当前结果：

- `core`：46 个测试（含 `WorkflowProviderContractTest` 抽象 TCK、模型构造校验、SPI 形状白名单）
- `workflow-infra-memory`：36 个测试（32 个 TCK 用例 + 4 个装配测试）
- 合计 82，全绿

TCK 覆盖：

- `start` 幂等、`actionId` 幂等、21 项能力声明
- 单人会签、全体会签、任一驳回、`RecordAndContinue` 死锁、`AtLeastRejects` 阈值
- BEFORE 挂起/恢复、AFTER 默认 `AnyApprove` 与下一步推进
- 转办不携带 record、委托 `onBehalfOf` 与 `completedBy` 双向可见、委托到期恢复
- 任务级撤回（record 保持）+ 中间任务 `TASK_CANCELLED`、实例级撤回
- `dueIn` 超时 `FAILED`、`timeoutPolicy=WAITING` + `ResumeWorkflowAction`
- AFTER × BEFORE 组合、AFTER 待派生丢弃（`REJECTED` / `UPSTREAM_ROLLBACK`）
- `AtLeastPercentage` 向上取整、减签、取消/终止、表达式参与者解析、搜索与版本冲突
- 终态委托清理、`withdraw` 不能撤销 REJECT 记录
- 终态催办拒绝、`SUSPENDED` 催办、抄送不改状态机
- `WAIT_SIGNAL` 名称校验、`pendingTaskIds` 与 `pendingFor` 一致

### 7.2 §11 checklist 状态

已实现并验证：契约依赖、幂等、持久化后返回（内存为锁内完成）、时钟、顺序、失败模型、
终态、补偿（仅状态）、能力声明、关闭、版本、发现、安全（operator 校验）、人工任务、
会签/或签、驳回终止、死锁终态、规则满足清理、审批动作、驳回白名单、BEFORE/AFTER、
AFTER × BEFORE、AFTER_ADDED 推进、加签位置校验、驳回重做、减签、转办/委托、
委托 × 会签、委托到期重算、终态 assignment 生命周期、嵌套委托、撤回、
撤回 record 语义、撤回并发、HUMAN_TASK 超时、SUSPENDED dueAt、Resume、抄送、催办、
任务查询、参与者解析、WAIT_SIGNAL、REJECTED 映射、待办可见性、构造器校验、活跃态定义。

`cancel(compensate=true)` 的 Saga 逆序补偿只记录状态与事件，不执行外部补偿——见 §5。
