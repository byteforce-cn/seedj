package cn.byteforce.seedj.starter.ai.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.cqrs.DecisionContext;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 事务装饰器：有上下文才设 GUC，无上下文完全委托。 */
class AgentDecisionTransactionRunnerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final AgentDecisionGuc guc = new AgentDecisionGuc(jdbc);

    private final AgentDecisionTransactionRunner runner =
            new AgentDecisionTransactionRunner(TransactionRunner.direct(), guc);

    @Test
    @DisplayName("上下文存在：进入事务后先设置 GUC")
    void appliesGucWhenContextIsOpen() {
        DecisionContext context =
                new DecisionContext(
                        UUID.randomUUID(), UUID.randomUUID(), "ticket.create", "sha256:a");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());

        try (var ignored = DecisionContext.open(context)) {
            String value = runner.inTransaction(() -> "ok");
            assertThat(value).isEqualTo("ok");
        }

        verify(jdbc)
                .queryForList(
                        anyString(),
                        eq(context.decisionId().toString()),
                        eq("ticket.create"),
                        eq("sha256:a"));
    }

    @Test
    @DisplayName("没有上下文：不碰 JDBC")
    void noContextDelegatesWithoutGuc() {
        assertThat(runner.inTransaction(() -> 42)).isEqualTo(42);
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }
}
