-- 决策面对账用的两条命名查询：outcome 派生与链校验补齐的唯一读入口。
--
-- 只读业务库、不写业务库：账本记录由 DecisionLedger 追加，这里只把「链上事实」取回来。
--
-- 限制一（U9 前）：seedj 的 @Audited 链条目 payload 契约固定为 {command_id, event_ids}，
-- AuditAppendRequest.action 也绑定命令类型（Audited.java / AuditAppendRequest.java），链上不写
-- decision_id / ledger_intent_id。因此「账本 outcome → 链条目」只能按 payload->>'command_id' 关联：
-- 可回放，但不是按 decision_id 的直链。U9 开放自定义条目/结果条目后，audit_by_command 应改为按
-- decision_id 直取，本文件的两条查询是那一步的唯一改动点。
--
-- 限制二（T0 有意收窄）：T0 没有跨租户扫描入口（PlaneReconciliationSource 的默认实现返回空列表），
-- 所以本文件只查「已指定 decision_id 的链上事实」，不提供「按时间扫待对账决策」的语句。
--
-- 限制三（索引，deferred）：audit_entry 没有 payload->>'command_id' 的表达式索引，audit_by_command
-- 每次都是顺序扫；T0 量级可接受，U9 落地后改用 decision_id 列 + 索引。
--
-- 载入方式：ReconcileQueries 按 "-- name:" 把本文件切成命名语句，语句里用 ? 作位置参数。

-- name: command_by_idempotency
select id, status, rejection_code, payload_fingerprint
  from command_record
 where issuer = ? and idempotency_key = ?

-- name: audit_by_decision
select id from audit_entry where payload->>'decision_id' = ? order by sequence limit 1

-- name: audit_by_command
select id from audit_entry where payload->>'command_id' = ? order by sequence limit 1
