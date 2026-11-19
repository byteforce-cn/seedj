package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 决策上下文的线程级作用域。 */
class DecisionContextTest {

    private static final DecisionContext A =
            new DecisionContext(UUID.randomUUID(), UUID.randomUUID(), "ticket.create", "sha256:a");
    private static final DecisionContext B =
            new DecisionContext(UUID.randomUUID(), UUID.randomUUID(), "ticket.comment", "sha256:b");

    @Test
    @DisplayName("嵌套作用域关闭后恢复上一值，最外层关闭后清空")
    void restoresPreviousValue() {
        try (var ignored = DecisionContext.open(A)) {
            assertThat(DecisionContext.current()).contains(A);
            try (var nested = DecisionContext.open(B)) {
                assertThat(DecisionContext.current()).contains(B);
            }
            assertThat(DecisionContext.current()).contains(A);
        }
        assertThat(DecisionContext.current()).isEmpty();
    }
}
