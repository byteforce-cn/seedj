package cn.byteforce.seedj.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令事件线格式：类型名、幂等键、版本与双时间顺序在构造期冻结。
 */
class CommandEventTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00.123456789Z");

    private static CommandEvent event(Instant occurredAt, Instant recordedAt, List<UUID> subjects,
                                      Map<String, Object> payload) {
        return new CommandEvent(ID, "hr.employee.updated", occurredAt, recordedAt, ACTOR,
                subjects, payload, "cmd-1:rule-1:0", 1);
    }

    @Test
    @DisplayName("时间截断到微秒，subjects 为空时归一为空列表")
    void normalisesTimesAndSubjects() {
        CommandEvent event = event(NOW, NOW, null, Map.of("k", "v"));

        assertThat(event.occurredAt().getNano() % 1000).isZero();
        assertThat(event.recordedAt().getNano() % 1000).isZero();
        assertThat(event.subjects()).isEmpty();
        assertThat(event.payload()).containsEntry("k", "v");
        assertThatThrownBy(() -> event.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("非法事件类型名被拒绝")
    void rejectsIllegalType() {
        assertThatThrownBy(() -> new CommandEvent(ID, "Hr.Employee", NOW, NOW, ACTOR, List.of(),
                Map.of(), "key", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal event type");
    }

    @Test
    @DisplayName("幂等键与版本在构造期校验")
    void rejectsBlankKeyAndBadVersion() {
        assertThatThrownBy(() -> new CommandEvent(ID, "hr.employee.updated", NOW, NOW, ACTOR,
                List.of(), Map.of(), " ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey must not be blank");
        assertThatThrownBy(() -> new CommandEvent(ID, "hr.employee.updated", NOW, NOW, ACTOR,
                List.of(), Map.of(), "key", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be >= 1");
    }

    @Test
    @DisplayName("occurred_at 不得晚于 recorded_at（T-R010）")
    void rejectsOccurredAfterRecorded() {
        assertThatThrownBy(() -> event(NOW.plusSeconds(1), NOW, List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("T-R010");
    }

    @Test
    @DisplayName("subjects 顺序保留且不可修改")
    void keepsSubjectOrder() {
        List<UUID> subjects = new ArrayList<>(List.of(ID, ACTOR));
        CommandEvent event = event(NOW, NOW, subjects, Map.of());

        subjects.clear();

        assertThat(event.subjects()).containsExactly(ID, ACTOR);
        assertThatThrownBy(() -> event.subjects().add(ID))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("事件类型与版本可派生到线上契约")
    void exposesWireFields() {
        CommandEvent event = event(NOW, NOW, List.of(ID), Map.of());

        assertThat(event.type()).isEqualTo("hr.employee.updated");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.idempotencyKey()).isEqualTo("cmd-1:rule-1:0");
        assertThat(event.actor()).isEqualTo(ACTOR);
        assertThat(event.subjects()).containsExactly(ID);
    }
}
