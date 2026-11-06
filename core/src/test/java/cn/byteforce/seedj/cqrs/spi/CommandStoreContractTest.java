package cn.byteforce.seedj.cqrs.spi;

import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommandStore} 契约测试套件。
 *
 * <p>实现方提供 {@link #store()} 与 {@link #reset()}。所有用例必须通过：
 * 内存实现与 PostgreSQL 实现跑同一套断言。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class CommandStoreContractTest {

    protected static final String TYPE = "probe.command";

    protected static final UUID ISSUER = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    protected static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");

    protected static final String FINGERPRINT = "a".repeat(64);

    /**
     * 被测存储。
     *
     * @return 存储实例
     */
    protected abstract CommandStore store();

    /**
     * 清空存储，保证用例隔离。
     */
    protected abstract void reset();

    @BeforeEach
    void cleanState() {
        reset();
    }

    protected CommandRecord pending(String idempotencyKey) {
        return pending(UUID.randomUUID(), idempotencyKey, Map.of("name", "Alice"));
    }

    protected CommandRecord pending(UUID id, String idempotencyKey, Map<String, Object> payload) {
        return CommandRecord.pending(id, TYPE, NOW, ISSUER, payload, idempotencyKey,
                FINGERPRINT, NOW);
    }

    @Test
    @DisplayName("按 ID 与幂等键都能查回同一条记录")
    void insertsAndFindsByIdAndKey() {
        CommandRecord record = pending("k-1");

        assertThat(store().insert(record)).isTrue();
        assertThat(store().find(record.id())).contains(record);
        assertThat(store().findByIdempotencyKey("k-1")).contains(record);
    }

    @Test
    @DisplayName("缺失记录返回空")
    void missingRecordsAreEmpty() {
        assertThat(store().find(UUID.randomUUID())).isEmpty();
        assertThat(store().findByIdempotencyKey("missing")).isEmpty();
    }

    @Test
    @DisplayName("重复幂等键不覆盖既有记录")
    void duplicateKeyIsRejectedWithoutOverwriting() {
        CommandRecord first = pending("k-2");
        CommandRecord second = pending("k-2");

        assertThat(store().insert(first)).isTrue();
        assertThat(store().insert(second)).isFalse();
        assertThat(store().findByIdempotencyKey("k-2"))
                .get()
                .extracting(CommandRecord::id)
                .isEqualTo(first.id());
    }

    @Test
    @DisplayName("重复 ID 同样被拒绝")
    void duplicateIdIsRejected() {
        CommandRecord first = pending("k-3");
        CommandRecord second = pending(first.id(), "k-4", Map.of());

        assertThat(store().insert(first)).isTrue();
        assertThat(store().insert(second)).isFalse();
    }

    @Test
    @DisplayName("接受终态保存 locator、version 与事件 ID")
    void completesAcceptedWithLocatorVersionAndEvents() {
        CommandRecord pending = pending("k-5");
        store().insert(pending);
        UUID locator = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        store().complete(pending.accepted(locator, 2L, List.of(eventId)));

        assertThat(store().find(pending.id())).get().satisfies(row -> {
            assertThat(row.status()).isEqualTo(CommandStatus.ACCEPTED);
            assertThat(row.locator()).isEqualTo(locator);
            assertThat(row.version()).isEqualTo(2L);
            assertThat(row.eventIds()).containsExactly(eventId);
        });
    }

    @Test
    @DisplayName("拒绝终态保存错误码与原因")
    void completesRejectedWithCodeAndReason() {
        CommandRecord pending = pending("k-6");
        store().insert(pending);

        store().complete(pending.rejected(ContractErrorCode.TEMPORAL_VIOLATION, "bad range"));

        assertThat(store().find(pending.id())).get().satisfies(row -> {
            assertThat(row.status()).isEqualTo(CommandStatus.REJECTED);
            assertThat(row.rejectionCode()).isEqualTo("EIS-400-003");
            assertThat(row.rejectionReason()).isEqualTo("bad range");
            assertThat(row.locator()).isNull();
            assertThat(row.version()).isNull();
        });
    }

    @Test
    @DisplayName("同一终态重复 complete 幂等")
    void sameTerminalCompletionIsIdempotent() {
        CommandRecord pending = pending("k-7");
        store().insert(pending);
        CommandRecord accepted = pending.accepted(UUID.randomUUID(), 1L, List.of());

        store().complete(accepted);
        store().complete(accepted);

        assertThat(store().find(pending.id())).get()
                .extracting(CommandRecord::status)
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    @Test
    @DisplayName("已进入终态后不能被另一个终态覆盖")
    void terminalStateCannotBeOverwritten() {
        CommandRecord pending = pending("k-8");
        store().insert(pending);
        store().complete(pending.accepted(UUID.randomUUID(), 1L, List.of()));

        assertThatThrownBy(() -> store().complete(
                pending.rejected(ContractErrorCode.TEMPORAL_VIOLATION, "late")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("complete 不存在的记录失败")
    void completingMissingRecordFails() {
        assertThatThrownBy(() -> store().complete(pending("k-9").accepted(
                UUID.randomUUID(), 1L, List.of())))
                .isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("abandon 移除 pending 并释放幂等键")
    void abandonRemovesPendingAndFreesKey() {
        CommandRecord pending = pending("k-abandon");
        store().insert(pending);

        store().abandon(pending.id());

        assertThat(store().find(pending.id())).isEmpty();
        assertThat(store().findByIdempotencyKey("k-abandon")).isEmpty();
        assertThat(store().insert(pending("k-abandon"))).isTrue();
    }

    @Test
    @DisplayName("abandon 不删除终态记录")
    void abandonLeavesTerminalRecord() {
        CommandRecord pending = pending("k-abandon-terminal");
        store().insert(pending);
        CommandRecord accepted = pending.accepted(UUID.randomUUID(), 1L, List.of());
        store().complete(accepted);

        store().abandon(pending.id());

        assertThat(store().find(pending.id())).get()
                .extracting(CommandRecord::status)
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    @Test
    @DisplayName("载荷与事件 ID 往返不丢结构")
    void payloadSurvivesRoundTrip() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("name", "Alice");
        nested.put("tags", List.of("a", "b"));
        nested.put("meta", Map.of("level", 2));
        CommandRecord record = pending(UUID.randomUUID(), "k-10", nested);

        assertThat(store().insert(record)).isTrue();

        CommandRecord loaded = store().find(record.id()).orElseThrow();
        assertThat(loaded.payload()).containsEntry("name", "Alice");
        assertThat(loaded.payload().get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(loaded.payload().get("meta")).isEqualTo(Map.of("level", 2));
    }
}
