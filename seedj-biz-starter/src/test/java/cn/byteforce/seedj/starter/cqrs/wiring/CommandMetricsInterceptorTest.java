package cn.byteforce.seedj.starter.cqrs.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.cqrs.Command;
import cn.byteforce.seedj.cqrs.CommandInvocation;
import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.domain.exception.AggregateNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandMetricsInterceptorTest {

    private final List<String> recorded = new ArrayList<>();

    private final CommandMetrics metrics =
            (command, outcome, took) ->
                    recorded.add(
                            command + '/' + outcome + '/' + (took.compareTo(Duration.ZERO) >= 0));

    private final CommandMetricsInterceptor<String> interceptor =
            new CommandMetricsInterceptor<>(metrics);

    @Test
    @DisplayName("成功命令记为 accepted")
    void successIsAccepted() {
        assertThat(interceptor.around(invocation(() -> "id-1"))).isEqualTo("id-1");
        assertThat(recorded).containsExactly("TestCommand/accepted/true");
    }

    @Test
    @DisplayName("业务拒绝记为 rejected")
    void commandRejectedIsRejected() {
        assertThatThrownBy(
                        () ->
                                interceptor.around(
                                        invocation(
                                                () -> {
                                                    throw new CqrsException(
                                                            CqrsErrorCode.COMMAND_REJECTED, "no");
                                                })))
                .isInstanceOf(CqrsException.class);
        assertThat(recorded).containsExactly("TestCommand/rejected/true");
    }

    @Test
    @DisplayName("并发冲突记为 conflict，聚合不存在记为 rejected，其他记为 error")
    void outcomeMapping() {
        assertThatThrownBy(
                        () ->
                                interceptor.around(
                                        invocation(
                                                () -> {
                                                    throw new CqrsException(
                                                            CqrsErrorCode.CONCURRENCY_CONFLICT,
                                                            "conflict");
                                                })))
                .isInstanceOf(CqrsException.class);

        assertThatThrownBy(
                        () ->
                                interceptor.around(
                                        invocation(
                                                () -> {
                                                    throw new AggregateNotFoundException(
                                                            "repo", "id");
                                                })))
                .isInstanceOf(AggregateNotFoundException.class);

        assertThatThrownBy(
                        () ->
                                interceptor.around(
                                        invocation(
                                                () -> {
                                                    throw new IllegalStateException("boom");
                                                })))
                .isInstanceOf(IllegalStateException.class);

        assertThat(recorded)
                .containsExactly(
                        "TestCommand/conflict/true",
                        "TestCommand/rejected/true",
                        "TestCommand/error/true");
    }

    private CommandInvocation<String> invocation(java.util.function.Supplier<String> body) {
        return new CommandInvocation<>() {
            @Override
            public Command<String> command() {
                return new TestCommand("id");
            }

            @Override
            public String aggregateId() {
                return "id";
            }

            @Override
            public String proceed() {
                return body.get();
            }
        };
    }

    record TestCommand(String id) implements Command<String> {
        @Override
        public String aggregateId() {
            return id;
        }
    }
}
