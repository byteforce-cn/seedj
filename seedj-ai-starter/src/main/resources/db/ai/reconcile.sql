-- 决策面对账用的两条命名查询：outcome 派生与链校验补齐的唯一读入口。
--
-- 只读业务库、不写业务库：账本记录由 DecisionLedger 追加，这里只把「链上事实」取回来。
--
-- 现状限制（U9 前）：seedj 的 @Audited 链条目 payload 契约固定为 {command_id, event_ids}，
-- AuditAppendRequest.action 也绑定命令类型（Audited.java / AuditAppendRequest.java），链上不写
-- decision_id / ledger_intent_id。因此「账本 outcome → 链条目」只能按 payload->>'command_id' 关联：
-- 可回放，但不是按 decision_id 的直链。U9 开放自定义条目/结果条目后，audit_by_command 应改为按
-- decision_id 直取，本文件的两条查询是那一步的唯一改动点。
--
-- command_by_idempotency 多取 payload 与 target_ref：排障时同一行就能看到命令原文与目标，不必再查一次；
-- 判定只用到 id / status / rejection_code / payload_fingerprint。
--
-- 载入方式：ReconcileQueries 按 "-- name:" 把本文件切成命名语句，语句里用 ? 作位置参数。

-- name: command_by_idempotency
select id, status, rejection_code, payload_fingerprint, payload, target_ref
  from command_record
 where issuer = ? and idempotency_key = ?

-- name: audit_by_command
select id from audit_entry where payload->>'command_id' = ? order by sequence limit 1
