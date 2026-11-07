package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandRegistrationTest {

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("test_counter", "counter")
                    .field("counter_id", FieldType.TEXT)
                    .build();

    private static final CommandDescriptor DESCRIPTOR =
            CommandDescriptor.builder()
                    .type("test.counter.increment")
                    .objectType("test_counter")
                    .payloadType(CommandAgentTest.Increment.class)
                    .permission("test.counter.write")
                    .build();

    private static CommandAgentTest.CounterAgent agent(boolean creates) {
        return new CommandAgentTest.CounterAgent(
                new CommandAgentTest.FakeRepository(new ArrayList<>()),
                new CommandAgentTest.RecordingPublisher(new ArrayList<>()),
                IdCodecs.strings(),
                CommandAgent.Options.defaults(),
                "counter-1",
                creates,
                new ArrayList<>());
    }

    @Test
    @DisplayName("handler 与 agent 恰好一个")
    void handlerAndAgentAreMutuallyExclusive() {
        assertThatThrownBy(
                        () ->
                                new CommandRegistration(
                                        DESCRIPTOR,
                                        new CommandAgentTest.NoopHandler(),
                                        agent(true),
                                        SCHEMA,
                                        Set.of(),
                                        Set.of(),
                                        List.of(),
                                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(
                        () ->
                                new CommandRegistration(
                                        DESCRIPTOR,
                                        null,
                                        null,
                                        SCHEMA,
                                        Set.of(),
                                        Set.of(),
                                        List.of(),
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("eventSourced 工厂从 agent 推导 creates")
    void eventSourcedFactoryUsesAgentCreateFlag() {
        CommandRegistration registration =
                CommandRegistration.eventSourced(
                        DESCRIPTOR,
                        agent(true),
                        SCHEMA,
                        Set.of(),
                        Set.of(),
                        List.of("test.counter.incremented"));

        assertThat(registration.isEventSourced()).isTrue();
        assertThat(registration.agent()).isNotNull();
        assertThat(registration.handler()).isNull();
        assertThat(registration.creates()).isTrue();
    }

    @Test
    @DisplayName("载荷类型必须等于 agent 解析出的命令类型")
    void payloadTypeMustMatchAgent() {
        CommandDescriptor mismatch =
                CommandDescriptor.builder()
                        .type("test.counter.increment")
                        .objectType("test_counter")
                        .payloadType(String.class)
                        .permission("test.counter.write")
                        .build();

        assertThatThrownBy(
                        () ->
                                CommandRegistration.eventSourced(
                                        mismatch,
                                        agent(true),
                                        SCHEMA,
                                        Set.of(),
                                        Set.of(),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("显式 creates 必须与 agent 一致")
    void createsMustMatchAgent() {
        assertThatThrownBy(
                        () ->
                                new CommandRegistration(
                                        DESCRIPTOR,
                                        null,
                                        agent(false),
                                        SCHEMA,
                                        Set.of(),
                                        Set.of(),
                                        List.of(),
                                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creates");
    }

    @Test
    @DisplayName("temporal 注册项不是 eventSourced")
    void temporalRegistrationIsNotEventSourced() {
        CommandRegistration registration =
                new CommandRegistration(DESCRIPTOR, new CommandAgentTest.NoopHandler(), SCHEMA);

        assertThat(registration.isEventSourced()).isFalse();
        assertThat(registration.agent()).isNull();
    }
}
