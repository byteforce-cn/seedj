package cn.byteforce.seedj.module;

import cn.byteforce.seedj.cqrs.Command;
import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.cqrs.IdCodecs;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Repository;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectModulesCommandTest {

    private static final CommandHandler<Object> HANDLER = new CommandHandler<>() {
        @Override
        public UUID targetId(Object command) {
            return UUID.randomUUID();
        }

        @Override
        public AppendRequest decide(Object command,
                                    cn.byteforce.seedj.cqrs.CommandContext ctx) {
            return AppendRequest.create("employee", ctx.targetId(), ctx.now(), null,
                    ctx.actor(), Map.of());
        }
    };

    @Test
    void commandDeclarationBuildsRegistration() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write")
                    .handler(HANDLER)
                    .creates()
                    .emits("hr.employee.created");

        ObjectModuleRegistry registry = modules.freeze();

        assertThat(registry.commands()).hasSize(1);
        CommandRegistration registration = registry.commands().get(0);
        assertThat(registration.descriptor().type()).isEqualTo("hr.employee.create");
        assertThat(registration.descriptor().permission()).isEqualTo("hr.employee.write");
        assertThat(registration.handler()).isSameAs(HANDLER);
        assertThat(registration.creates()).isTrue();
        assertThat(registry.commandDeclarations().get(0).creates()).isTrue();
        assertThat(registry.commandDeclarations().get(0).emits())
                .containsExactly("hr.employee.created");
    }

    @Test
    void duplicateCommandTypeFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write").handler(HANDLER);
        modules.object("employee2", "hr_employee_2")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write").handler(HANDLER);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate command type");
    }

    @Test
    void missingPermissionFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .handler(HANDLER);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void missingHandlerFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write");

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handler");
    }

    @Test
    void redactedCommandFieldIsExplicit() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreateExtraPayload.class)
                    .permission("hr.employee.write")
                    .handler(HANDLER)
                    .redactedCommandField("id_number");

        ObjectModuleRegistry registry = modules.freeze();
        CommandRegistration registration = registry.commands().get(0);

        assertThat(registration.commandFields()).containsExactly("id_number");
        assertThat(registration.redactedFields()).containsExactly("id_number");
        assertThat(registry.commandDeclarations().get(0).redactedFields())
                .containsExactly("id_number");
    }

    @Test
    void payloadFieldOutsideSchemaFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreateExtraPayload.class)
                    .permission("hr.employee.write").handler(HANDLER);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id_number");
    }

    @Test
    void commandFieldExemptsPayloadField() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreateExtraPayload.class)
                    .permission("hr.employee.write")
                    .handler(HANDLER)
                    .commandField("id_number");

        assertThat(modules.freeze().commands()).hasSize(1);
    }

    private static final CommandAgent<AgentPayload, AgentAggregate, String> AGENT =
            new TestAgent(true);

    private static final CommandAgent<AgentPayload, AgentAggregate, String> NON_CREATING =
            new TestAgent(false);

    @Test
    void agentCommandBuildsEventSourcedRegistration() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.onboard", AgentPayload.class)
                    .permission("hr.employee.write")
                    .agent(AGENT)
                    .emits("test.probe.touched");

        CommandRegistration registration = modules.freeze().commands().get(0);

        assertThat(registration.isEventSourced()).isTrue();
        assertThat(registration.handler()).isNull();
        assertThat(registration.agent()).isSameAs(AGENT);
        assertThat(registration.creates()).isTrue();
        assertThat(registration.descriptor().payloadType()).isEqualTo(AgentPayload.class);
    }

    @Test
    void handlerAndAgentTogetherFailAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.onboard", AgentPayload.class)
                    .permission("hr.employee.write")
                    .handler(HANDLER)
                    .agent(AGENT);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void agentPayloadTypeMismatchFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.onboard", CreatePayload.class)
                    .permission("hr.employee.write")
                    .agent(AGENT);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void explicitCreatesMustMatchAgent() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.onboard", AgentPayload.class)
                    .permission("hr.employee.write")
                    .agent(NON_CREATING)
                    .creates();

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creates");
    }

    record AgentPayload(String name) implements Command<String> {

        @Override
        public String aggregateId() {
            return "agg-1";
        }
    }

    static final class AgentAggregate extends AbstractAggregateRoot<String> {

        AgentAggregate(String id) {
            super(id);
        }
    }

    static final class TestAgent extends CommandAgent<AgentPayload, AgentAggregate, String> {

        private final boolean creates;

        TestAgent(boolean creates) {
            super(new AgentRepository(), new NoopPublisher(), IdCodecs.strings());
            this.creates = creates;
        }

        @Override
        protected String aggregateType() {
            return "test_probe";
        }

        @Override
        protected boolean createsOnEmpty() {
            return creates;
        }

        @Override
        protected AgentAggregate create(String id, AgentPayload command) {
            return new AgentAggregate(id);
        }

        @Override
        protected void handle(AgentAggregate aggregate, AgentPayload command) {
        }
    }

    static final class AgentRepository implements Repository<AgentAggregate, String> {

        @Override
        public Optional<AgentAggregate> load(String id) {
            return Optional.empty();
        }

        @Override
        public void save(AgentAggregate aggregate) {
        }
    }

    static final class NoopPublisher implements EventPublisher {

        @Override
        public void publish(cn.byteforce.seedj.domain.event.DomainEvent event) {
        }

        @Override
        public void publish(EventMessage message) {
        }
    }

    record CreatePayload(String name) {
    }

    record CreateExtraPayload(String name, String idNumber) {
    }
}
