# 工作流 coord 适配器

`seedj-workflow-coord` 把 `WorkflowProvider`（15 个方法 / 21 项能力）适配到 coord 的
workflow v2 审批面。业务代码仍只依赖 `WorkflowProvider`；`seedj-workflow-local` 仍是默认
provider，本模块是并列的生产适配器。

## 装配

```yaml
seedj:
  coord:
    enabled: true
    agent-host: 127.0.0.1
    agent-port: 13135
    namespace: seedj:prod           # 可选；缺省 seedj:<spring.application.name>
  workflow:
    enabled: true
    provider: coord                 # memory（默认）| coord
    coord:
      sign-admin: seedj-server      # 必填；服务主体
      max-definition-bytes: 65536   # 内嵌定义上限
```

coord agent 侧必须把服务主体加入服务代终端用户白名单，否则所有席位动作（审批/驳回/转办/
委托/撤回/催办/抄送/恢复）都会被拒：

```toml
[services]
workflow_on_behalf_services = ["seedj-server"]
```

`sign-admin` 必须等于该服务主体：它被编译进每个 `approve.signAdmins`，并用于加签、减签、
终止。缺失时启动失败（`seedj.workflow.coord.sign-admin is required`）。

## 映射摘要

| seedj | coord |
| --- | --- |
| 定义 `register/findDefinition` | `Deploy` / `GetDefinition` / `ListDefinitionVersions`；seedj 定义原样内嵌 `document.tags["seedj.definition"]` |
| 实例 `start` | `StartByDefinitionWithIdOnBehalfOf`（发起人 = 业务用户） |
| `HumanTask` | 审批轮次（`Round`）；`TaskId` = `<instanceId>:round-NNNN` |
| 席位/assignment | 审批任务（`ApprovalTask`） |
| `Approve/Reject/Transfer/Delegate` | 同名 RPC，`on_behalf_of` = 终端用户 |
| `Withdraw`（任务级） | `WithdrawTask`（保留通过记录，回退到目标节点） |
| `WithdrawInstance` | 工作流 `Cancel`（实例收口为 CANCELLED），席位/事件词汇由读面补齐 |
| `AddAssignee/RemoveAssignee` | `AddSign`/`RemoveSign`，服务主体执行，真实 operator 落 sidecar |
| `Urge/Cc` | `Urge`/`Cc`，服务主体执行 |
| `ResumeWorkflow` | `ResumeWorkflow`；时间预算取步骤 `timeout`（coord 0.4.6+） |
| `cancel/terminate` | 工作流 `Cancel` / 审批 `Terminate`（无审批聚合时回退 runtime） |
| `search` | `ListInstances`（`status_in` 多值；没有该字段的旧集群走客户端过滤） |
| `history` | `History` + seat→round 映射 + sidecar 补齐 |

定义编译是 fail-closed 的：多条前向驳回目标（coord 的 `onReject` 是定义期常量）、未知
`timeoutPolicy`、不存在的驳回目标、定义体超过 `max-definition-bytes` 都会在 `register` 时
拒绝，不会带着丢失的语义启动。

## sidecar（coord KV）

coord 不建模、但 seedj 契约需要的补充事实写在 coord KV：

- `formData`：`ApproveAction.formData` 在命令前写入；写失败 → 动作拒绝（表单不能静默丢）。
- action extras：真实 operator、撤回目标步、续期时长；写失败只 WARN，coord 命令仍是权威。
- 键空间：`<coord namespace>:workflow:sidecar:<instanceId>:...`，无 TTL，保留由运维负责。
  读路径遇到坏 JSON 会 WARN 并忽略该键。

## 已知边界

- **非 USER 受让方**：`Transfer/Delegate` 的组/部门/角色受让方 fail-closed（coord 只接受单一
  principal，coord#454）。
- **无 `approve` 节点的实例**：`terminate()` 走工作流 runtime 回退（coord#456 已修）；仍不
  建议对纯 ACTIVITY 实例使用审批语义。
- **时间戳精度**：`assignedAt` 取轮次开启时间、`resolvedAt` 取投票时间；取消/过期/撤回的
  `resolvedAt` 只能取实例或轮次更新时间。
- **完成后加签**：coord 只允许在 open 分支上创建 AFTER 轮次。适配器把对已完成步骤的后加签
  挂到当前 open 分支，sidecar 记录真正的原始任务；该轮次在上游回退时同样被取消，但它不会
  回填到已完成步骤的当前状态里。coord 支持后删除这条投影。
- **实例撤回的回退事件**：coord 的实例撤回不投影完整的席位级事件，适配器按 sidecar 的
  `targetStep` 补齐 `TASK_WITHDRAWN` / `INSTANCE_ROLLED_BACK`；coord 补齐后删除投影。
- **恢复的两段提交**：coord 先建新轮次、随后才落库 `INSTANCE_RESUMED` 与 runtime 的
  `SUSPENDED`，`GetStatus` 还可能滞后 2–3s。适配器以 `GetInstanceDetail` 为权威读面，
  把 `INSTANCE_RESUMED` 前移到本轮次事件之前，并据此修正「恢复后仍报 WAITING」与
  「极短期限超时丢掉 runtime Waiting」两个窗口；coord 合并提交后删除投影。
- **实例级 Cc** 的 operator 仍受 coord 授权面约束（initiator / `sign-admins` / 席位持有人）；
  任务级 Cc 由 `allowCc` 约束。
- **retry**：coord 0.4.6 支持 `maxDelay` 与 `multiplier`；seedj 的退避序列按 `delay ×
  multiplier^(n-1)` 落进 coord，不再需要 fail-closed。

## 验证

契约 TCK（31 个用例）跑在真 coord 集群上：

```bash
./mvnw -o -B -pl seedj-workflow-coord test -Dtest=CoordWorkflowProviderContractTest
```

需要 Linux + Docker，镜像由 `seedj.coord.test.image` 覆盖（默认与 `config/conformance/coord-ref.txt`
一致）。无 Docker 时整类跳过，而不是降级到内存 provider。
