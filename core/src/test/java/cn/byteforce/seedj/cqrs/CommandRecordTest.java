package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 命令记录的形状约束：终态字段只在对应状态出现，线格式字段不可为空。 */
class CommandRecordTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ISSUER = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final UUID LOCATOR = UUID.fromString("018f2e3a-1234-7000-8000-000000000002");

    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");

    private static final String FINGERPRINT = "0".repeat(64);

    private static CommandRecord pending() {
        return CommandRecord.pending(
                ID,
                "hr.employee.onboard",
                NOW,
                ISSUER,
                Map.of("name", "Alice"),
                "key-1",
                FINGERPRINT,
                NOW);
    }

    @Test
    @DisplayName("pending 记录没有终态字段")
    void pendingRecordHasNoTerminalFields() {
        CommandRecord record = pending();

        assertThat(record.status()).isEqualTo(CommandStatus.PENDING);
        assertThat(record.locator()).isNull();
        assertThat(record.version()).isNull();
        assertThat(record.rejectionCode()).isNull();
        assertThat(record.rejectionReason()).isNull();
        assertThat(record.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("accepted 记录必须有 locator 与 version")
    void acceptedRequiresLocatorAndVersion() {
        CommandRecord record = pending().accepted(LOCATOR, 1L, List.of());

        assertThat(record.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(record.locator()).isEqualTo(LOCATOR);
        assertThat(record.version()).isEqualTo(1L);
        assertThat(record.isTerminal()).isTrue();

        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.ACCEPTED,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locator");
    }

    @Test
    @DisplayName("accepted 记录可以用 target_ref 代替 locator")
    void acceptedWithTargetRef() {
        CommandRecord record = pending().accepted("emp-1", 2L, List.of());

        assertThat(record.locator()).isNull();
        assertThat(record.targetRef()).isEqualTo("emp-1");
        assertThat(record.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("accepted 恰好要求 locator 或 target_ref 之一")
    void acceptedRequiresExactlyOneTarget() {
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.ACCEPTED,
                                        null,
                                        null,
                                        List.of(),
                                        LOCATOR,
                                        1L,
                                        "emp-1",
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.ACCEPTED,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        1L,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("target_ref 最长 255 且不得空白")
    void targetRefIsBounded() {
        assertThatThrownBy(() -> pending().accepted(" ", 1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetRef");
        assertThatThrownBy(() -> pending().accepted("x".repeat(256), 1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("rejected 记录必须有 code 与 reason，线格式为小写")
    void rejectedRequiresCodeAndReasonAndWireNamesAreLowercase() {
        CommandRecord record =
                pending().rejected(ContractErrorCode.TEMPORAL_VIOLATION, "bad range");

        assertThat(record.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(record.status().wireName()).isEqualTo("rejected");
        assertThat(record.rejectionCode()).isEqualTo("EIS-400-003");
        assertThat(record.rejectionReason()).isEqualTo("bad range");
        assertThat(record.isTerminal()).isTrue();

        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.REJECTED,
                                        "EIS-400-003",
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("状态线格式只接受三个小写值")
    void wireStatusRoundTrip() {
        assertThat(CommandStatus.fromWire("pending")).isEqualTo(CommandStatus.PENDING);
        assertThat(CommandStatus.fromWire("accepted")).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(CommandStatus.fromWire("rejected")).isEqualTo(CommandStatus.REJECTED);
        assertThatThrownBy(() -> CommandStatus.fromWire("PENDING"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("payload 允许 null 值，且构造后不可变")
    void payloadMayContainNullValuesAndIsImmutable() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Alice");
        payload.put("note", null);

        CommandRecord record =
                CommandRecord.pending(
                        ID, "hr.employee.onboard", NOW, ISSUER, payload, "key-1", FINGERPRINT, NOW);

        assertThat(record.payload()).containsEntry("note", null);
        assertThatThrownBy(() -> record.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("时间统一截断到微秒")
    void timesAreTruncatedToMicroseconds() {
        Instant withNanos = NOW.plus(1, ChronoUnit.MICROS).plusNanos(123);
        CommandRecord record =
                CommandRecord.pending(
                        ID,
                        "hr.employee.onboard",
                        withNanos,
                        ISSUER,
                        Map.of(),
                        "key-1",
                        FINGERPRINT,
                        withNanos);

        assertThat(record.issuedAt()).isEqualTo(NOW.plus(1, ChronoUnit.MICROS));
        assertThat(record.createdAt().getNano() % 1000).isZero();
    }

    @Test
    @DisplayName("类型与指纹在构造期拒绝非法值")
    void rejectsIllegalTypeAndFingerprint() {
        assertThatThrownBy(
                        () ->
                                CommandRecord.pending(
                                        ID,
                                        "Hr.Employee",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(
                        () ->
                                CommandRecord.pending(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        "not-a-digest",
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test
    @DisplayName("幂等键长度上限与空白校验")
    void rejectsIllegalIdempotencyKey() {
        assertThatThrownBy(
                        () ->
                                CommandRecord.pending(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        " ",
                                        FINGERPRINT,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey must not be blank");
        assertThatThrownBy(
                        () ->
                                CommandRecord.pending(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "k".repeat(256),
                                        FINGERPRINT,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longer than 255");
    }

    @Test
    @DisplayName("pending 记录必须带租约且不得携带终态字段")
    void pendingRecordGuards() {
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.PENDING,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        NOW,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending command requires a lease");
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.PENDING,
                                        null,
                                        null,
                                        List.of(),
                                        LOCATOR,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending command must not carry locator or version");
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.PENDING,
                                        "EIS-400-003",
                                        "why",
                                        List.of(),
                                        null,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending command must not carry rejection fields");
    }

    @Test
    @DisplayName("accepted 记录拒绝零版本与拒绝字段")
    void acceptedRecordGuards() {
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.ACCEPTED,
                                        null,
                                        null,
                                        List.of(),
                                        LOCATOR,
                                        0L,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version >= 1");
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.ACCEPTED,
                                        "EIS-400-003",
                                        null,
                                        List.of(),
                                        LOCATOR,
                                        1L,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry rejection fields");
    }

    @Test
    @DisplayName("rejected 记录拒绝 locator/version、非契约错误码与空原因")
    void rejectedRecordGuards() {
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.REJECTED,
                                        "EIS-400-003",
                                        "why",
                                        List.of(),
                                        LOCATOR,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry locator or version");
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.REJECTED,
                                        "OPAQUE-1",
                                        "why",
                                        List.of(),
                                        null,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contract error code");
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.REJECTED,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contract error code");
        assertThatThrownBy(
                        () ->
                                new CommandRecord(
                                        ID,
                                        "hr.employee.onboard",
                                        NOW,
                                        ISSUER,
                                        Map.of(),
                                        "key-1",
                                        FINGERPRINT,
                                        CommandStatus.REJECTED,
                                        "EIS-400-003",
                                        " ",
                                        List.of(),
                                        null,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejection reason");
    }

    @Test
    @DisplayName("租约为空时保留 null，非空时截断到微秒")
    void leaseIsOptionalAndTruncated() {
        Instant lease = NOW.plus(1, ChronoUnit.MICROS).plusNanos(999);
        CommandRecord record =
                CommandRecord.pending(
                        ID,
                        "hr.employee.onboard",
                        NOW,
                        ISSUER,
                        Map.of(),
                        "key-1",
                        FINGERPRINT,
                        NOW,
                        lease);
        CommandRecord withoutLease =
                new CommandRecord(
                        ID,
                        "hr.employee.onboard",
                        NOW,
                        ISSUER,
                        Map.of(),
                        "key-1",
                        FINGERPRINT,
                        CommandStatus.ACCEPTED,
                        null,
                        null,
                        List.of(),
                        LOCATOR,
                        1L,
                        NOW,
                        null);

        assertThat(record.leaseUntil()).isEqualTo(NOW.plus(1, ChronoUnit.MICROS));
        assertThat(withoutLease.leaseUntil()).isNull();
        assertThat(withoutLease.eventIds()).isEmpty();
    }
}
