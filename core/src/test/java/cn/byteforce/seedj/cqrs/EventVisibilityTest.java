package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.domain.event.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventVisibilityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("employee", "hr_employee")
            .field("name", FieldType.TEXT)
            .field(FieldDefinition.writeOnly("id_number", FieldType.TEXT))
            .field(new FieldDefinition("salary", FieldType.DECIMAL, false, false, false,
                    false, false, false, Privacy.SENSITIVE, null))
            .build();

    @Test
    @DisplayName("事件携带 writeOnly 字段失败关闭")
    void writeOnlyFieldInEventFailsClosed() {
        assertThatThrownBy(() -> EventVisibility.requireVisible(
                List.of(new LeakyEvent("张三", "110101")), SCHEMA, JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id_number");
    }

    @Test
    @DisplayName("事件携带 sensitive 字段失败关闭")
    void sensitiveFieldInEventFailsClosed() {
        assertThatThrownBy(() -> EventVisibility.requireVisible(
                List.of(new SensitiveEvent("张三", new BigDecimal("1000"))), SCHEMA, JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("salary");
    }

    @Test
    @DisplayName("schema 之外的额外事件字段允许")
    void unknownEventFieldsAreAllowed() {
        EventVisibility.requireVisible(List.of(new SafeEvent("张三", "emp-1")), SCHEMA, JSON);
    }

    @Test
    @DisplayName("空事件列表不失败")
    void emptyEventsPass() {
        EventVisibility.requireVisible(List.of(), SCHEMA, JSON);
    }

    record LeakyEvent(String name, String idNumber) implements DomainEvent {
    }

    record SensitiveEvent(String name, BigDecimal salary) implements DomainEvent {
    }

    record SafeEvent(String name, String aggregateId) implements DomainEvent {
    }
}
