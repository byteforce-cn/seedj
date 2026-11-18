package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.core.Migration;
import java.util.List;

/**
 * 决策账本的版本化迁移：链头表 + 只追加的记录表 + 拒绝改写的触发器。
 *
 * <p>记录表的写权限只有 INSERT/SELECT：{@code before update or delete} 触发器在数据库层拒绝改写， 与「账本只能追加」的
 * SPI 语义互为双保险（触发器是最后一道，应用层不该依赖它来发现 bug）。
 *
 * <p>触发器函数用单引号字符串作为函数体（而不是 {@code $$} 美元引用）：{@code JdbcMigrationRunner} 用 Spring
 * 的脚本执行器按 {@code ;} 切分语句，它不识别美元引用，会把函数体从中间的 {@code ;} 切开。
 */
final class AiMigrations {

    private AiMigrations() {}

    static List<Migration> all() {
        return List.of(
                new Migration(
                        1,
                        "agent decision ledger",
                        """
                        create table agent_decision_chain (
                          partition text primary key, last_sequence bigint not null,
                          last_hash char(64) not null);
                        create table agent_decision_record (
                          record_id uuid primary key,
                          decision_id uuid not null,
                          record_kind text not null
                            check (record_kind in ('intent','approval','outcome','reconciled')),
                          partition text not null references agent_decision_chain(partition),
                          sequence bigint not null,
                          prev_hash char(64) not null,
                          record_hash char(64) not null,
                          recorded_at timestamptz not null,
                          tenant_id uuid not null,
                          schema_version text not null,
                          body jsonb not null,
                          unique (partition, sequence));
                        create unique index agent_decision_intent_uk
                          on agent_decision_record (decision_id) where record_kind='intent';
                        create unique index agent_decision_outcome_uk
                          on agent_decision_record (decision_id) where record_kind='outcome';
                        create index agent_decision_by_decision
                          on agent_decision_record (decision_id, sequence);
                        create function agent_decision_immutable() returns trigger language plpgsql
                          as 'begin raise exception ''agent_decision_record is append-only''; end';
                        create trigger agent_decision_no_update before update or delete
                          on agent_decision_record
                          for each row execute function agent_decision_immutable();
                        """));
    }
}
