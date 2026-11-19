package cn.byteforce.seedj.starter.ai.guard;

import cn.byteforce.seedj.cqrs.DecisionContext;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 在命令事务内设置 RLS 需要的 GUC：{@code agent.decision_id} / {@code agent.command_type} / {@code agent.args_hash}。
 *
 * <p>{@code set_config(..., true)} 是事务级设置，事务结束自动清除；没有 {@link DecisionContext} 时 no-op，
 * 不影响非 AI 事务。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AgentDecisionGuc {

    private static final String SET =
            """
            select set_config('agent.decision_id', ?, true),
                   set_config('agent.command_type', ?, true),
                   set_config('agent.args_hash', ?, true)
            """;

    private final JdbcTemplate jdbc;

    public AgentDecisionGuc(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 当前线程有决策上下文时设置 GUC。 */
    public void apply() {
        DecisionContext context = DecisionContext.current().orElse(null);
        if (context == null) {
            return;
        }
        jdbc.queryForList(
                SET,
                context.decisionId().toString(),
                context.commandType(),
                context.argsHash());
    }
}
