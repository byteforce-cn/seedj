package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 命令描述与权限策略的启动期约束。 */
class CommandDescriptorTest {

    private static final Caller WRITER =
            new Caller("u-1", "alice", "default", Set.of(), Set.of("hr.employee.write"),
                    Set.of(), Set.of("pwd"), Caller.DEFAULT_ACR, Map.of());

    @Test
    @DisplayName("合法描述保留声明值，validator/redactor 缺省为非空 noop")
    void validDescriptorKeepsDeclaredValues() {
        CommandDescriptor descriptor = CommandDescriptor.builder()
                .type("hr.employee.create")
                .objectType("employee")
                .payloadType(Map.class)
                .permission("hr.employee.write")
                .build();

        assertThat(descriptor.type()).isEqualTo("hr.employee.create");
        assertThat(descriptor.objectType()).isEqualTo("employee");
        assertThat(descriptor.payloadType()).isEqualTo(Map.class);
        assertThat(descriptor.permission()).isEqualTo("hr.employee.write");
        assertThat(descriptor.validator()).isNotNull();
        assertThat(descriptor.redactor()).isNotNull();
        assertThatCode(() -> descriptor.validator().validate(new Object())).doesNotThrowAnyException();
        assertThat(descriptor.redactor().redact(Map.of("a", 1))).containsEntry("a", 1);
    }

    @Test
    @DisplayName("命令类型必须匹配线格式")
    void typeMustMatchWireFormat() {
        for (String invalid : List.of("Hr.Employee.Create", "1command", "hr-employee", "hr employee")) {
            assertThatThrownBy(() -> descriptor(invalid))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("command type");
        }
        assertThatCode(() -> descriptor("hr.employee.create_v2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("objectType / payloadType 必填")
    void objectTypeAndPayloadTypeAreRequired() {
        assertThatThrownBy(() -> new CommandDescriptor("hr.employee.create", " ", Map.class, null,
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objectType");
        assertThatThrownBy(() -> new CommandDescriptor("hr.employee.create", "employee", null, null,
                null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payloadType");
    }

    @Test
    @DisplayName("builder 缺字段在构造期失败")
    void builderFailsOnMissingFields() {
        assertThatThrownBy(() -> CommandDescriptor.builder().objectType("employee")
                .payloadType(Map.class).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> CommandDescriptor.builder().type("hr.employee.create")
                .payloadType(Map.class).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("objectType");
        assertThatThrownBy(() -> CommandDescriptor.builder().type("hr.employee.create")
                .objectType("employee").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payloadType");
    }

    @Test
    @DisplayName("权限策略：缺权限是装配错误，无权限是 403")
    void permissionPolicyFailsClosed() {
        CommandPolicy policy = CommandPolicy.permissions();
        Map<String, Object> payload = Map.of();

        assertThatThrownBy(() -> policy.authorize(descriptorWithoutPermission(), WRITER, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no permission");

        assertThatThrownBy(() -> policy.authorize(
                descriptor("hr.employee.create"), callerWithoutPermission(), payload))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));

        assertThatCode(() -> policy.authorize(descriptor("hr.employee.create"), WRITER, payload))
                .doesNotThrowAnyException();
    }

    private static CommandDescriptor descriptor(String type) {
        return CommandDescriptor.builder()
                .type(type)
                .objectType("employee")
                .payloadType(Map.class)
                .permission("hr.employee.write")
                .build();
    }

    private static CommandDescriptor descriptorWithoutPermission() {
        return CommandDescriptor.builder()
                .type("hr.employee.create")
                .objectType("employee")
                .payloadType(Map.class)
                .build();
    }

    private static Caller callerWithoutPermission() {
        return new Caller("u-2", "bob", "default", Set.of(), Set.of("hr.employee.read"),
                Set.of(), Set.of("pwd"), Caller.DEFAULT_ACR, Map.of());
    }
}
