package cn.byteforce.seedj.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.audit.spi.AuditStore;
import cn.byteforce.seedj.core.ErrorCategory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 审计装配守卫：存储/ID 生成器返回 null 时快速失败，值对象保留全部字段。 */
class AuditChainGuardTest {

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("nextSequenceAndPrevHash 返回 null：append 立即失败")
    void appendRejectsNullHead() {
        AuditChain<UUID> chain =
                new AuditChain<>(new NullHeadStore(), () -> NOW, head -> {}, () -> ACTOR);

        assertThatThrownBy(
                        () ->
                                chain.append(
                                        new AuditAppendRequest<>(
                                                "voucher.create",
                                                ACTOR,
                                                "voucher",
                                                null,
                                                JsonMapper.builder().build().createObjectNode())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null head");
    }

    @Test
    @DisplayName("ID 生成器返回 null：append 立即失败")
    void appendRejectsNullId() {
        AuditChain<UUID> chain =
                new AuditChain<>(new InMemoryStore(), () -> NOW, head -> {}, () -> null);

        assertThatThrownBy(
                        () ->
                                chain.append(
                                        new AuditAppendRequest<>(
                                                "voucher.create", ACTOR, "voucher", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id generator");
    }

    @Test
    @DisplayName("head 为 null：head() 立即失败")
    void headRejectsNullStoreHead() {
        AuditChain<UUID> chain =
                new AuditChain<>(new NullHeadStore(), () -> NOW, head -> {}, () -> ACTOR);

        assertThatThrownBy(chain::head)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null head");
    }

    @Test
    @DisplayName("AuditHead：暴露序号、哈希与锚定时刻")
    void auditHeadAccessors() {
        AuditHead head = new AuditHead(3, "abc", NOW);

        assertThat(head.getSequence()).isEqualTo(3);
        assertThat(head.getHash()).isEqualTo("abc");
        assertThat(head.getAnchoredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("AuditEntry：payload digest 必须是 64 位小写 hex")
    void auditEntryRejectsMalformedDigest() {
        assertThatThrownBy(
                        () ->
                                new AuditEntry<>(
                                        ACTOR,
                                        1,
                                        NOW,
                                        ACTOR,
                                        "a",
                                        "t",
                                        null,
                                        null,
                                        "DEADBEEF",
                                        null,
                                        CanonicalHasher.GENESIS_PREV_HASH,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 lowercase hex");
    }

    @Test
    @DisplayName("AuditEntry：signature 不参与哈希但可读取")
    void auditEntrySignatureIsReadable() {
        AuditEntry<UUID> entry =
                new AuditEntry<>(
                        ACTOR,
                        1,
                        NOW,
                        ACTOR,
                        "a",
                        "t",
                        null,
                        null,
                        null,
                        "hash",
                        CanonicalHasher.GENESIS_PREV_HASH,
                        "signature-1");

        assertThat(entry.getSignature()).isEqualTo("signature-1");
        assertThat(entry.getActor()).isEqualTo(ACTOR);
        assertThat(entry.getResourceId()).isNull();
        assertThat(entry.getPayload()).isNull();
        assertThat(entry.getPayloadDigest()).isNull();
    }

    @Test
    @DisplayName("AuditEvent：payload 为 null 时写入空对象")
    void auditEventDefaultsPayloadToEmptyObject() {
        UUID eventId = UUID.fromString("018f2e3a-aaaa-7000-8000-000000000001");
        AuditEvent event = new AuditEvent(eventId, "voucher.created", null);

        assertThat(event.id()).isEqualTo(eventId);
        assertThat(event.type()).isEqualTo("voucher.created");
        assertThat(event.payload().isObject()).isTrue();
        assertThat(event.payload().isEmpty()).isTrue();

        assertThatThrownBy(() -> new AuditEvent(null, "type", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEvent(eventId, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type");
        assertThatThrownBy(() -> new AuditEvent(eventId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AuditException：暴露错误码与分类")
    void auditException() {
        AuditException error = new AuditException(AuditErrorCode.TX_REQUIRED, "no tx");

        assertThat(error.getErrorCode()).isEqualTo(AuditErrorCode.TX_REQUIRED);
        assertThat(error.category()).isEqualTo(ErrorCategory.CONFIG_ERROR);
        assertThat(error.isRetryable()).isFalse();
        assertThat(error.getMessage()).isEqualTo("no tx");

        IllegalStateException cause = new IllegalStateException("db");
        AuditException withCause =
                new AuditException(AuditErrorCode.STORE_UNAVAILABLE, "down", cause);
        assertThat(withCause.getErrorCode()).isEqualTo(AuditErrorCode.STORE_UNAVAILABLE);
        assertThat(withCause.category()).isEqualTo(ErrorCategory.BACKEND_ERROR);
        assertThat(withCause.isRetryable()).isTrue();
        assertThat(withCause.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("AuditErrorCode：分类与默认可重试")
    void auditErrorCode() {
        assertThat(AuditErrorCode.STORE_MISSING.category()).isEqualTo(ErrorCategory.CONFIG_ERROR);
        assertThat(AuditErrorCode.STORE_MISSING.defaultRetryable()).isFalse();
        assertThat(AuditErrorCode.APPEND_FAILED.category()).isEqualTo(ErrorCategory.BACKEND_ERROR);
        assertThat(AuditErrorCode.APPEND_FAILED.defaultRetryable()).isTrue();
    }

    @Test
    @DisplayName("CanonicalHasher.normalizeId：UUID 小写，其它走 toString")
    void canonicalHasherNormalizeId() {
        UUID uuid = UUID.fromString("018F2E3A-0000-7000-8000-000000000001");

        assertThat(CanonicalHasher.normalizeId(uuid))
                .isEqualTo("018f2e3a-0000-7000-8000-000000000001");
        assertThat(CanonicalHasher.normalizeId("Actor-1")).isEqualTo("Actor-1");
        assertThat(CanonicalHasher.normalizeId(42)).isEqualTo("42");
        assertThat(CanonicalHasher.normalizeId(null)).isEmpty();
    }

    @Test
    @DisplayName("AuditAppendRequest：getEvents 保留 null 与空列表的差异")
    void appendRequestEvents() {
        assertThat(new AuditAppendRequest<>("a", ACTOR, "t", null, null).getEvents()).isNull();
        assertThat(new AuditAppendRequest<>("a", ACTOR, "t", null, null, List.of()).getEvents())
                .isEmpty();
        assertThat(new AuditAppendRequest<>("a", ACTOR, "t", null, null).getAction())
                .isEqualTo("a");
        assertThat(new AuditAppendRequest<>("a", ACTOR, "t", null, null).getActor())
                .isEqualTo(ACTOR);
        assertThat(new AuditAppendRequest<>("a", ACTOR, "t", null, null).getResourceType())
                .isEqualTo("t");
        assertThat(new AuditAppendRequest<>("a", ACTOR, "t", null, null).getResourceId()).isNull();
        assertThat(new AuditAppendRequest<>("a", ACTOR, "t", null, null).getPayload()).isNull();
    }

    private static final class NullHeadStore implements AuditStore<UUID> {

        @Override
        public Head nextSequenceAndPrevHash() {
            return null;
        }

        @Override
        public void insert(AuditEntry<UUID> entry) {}

        @Override
        public void updateHead(String hash) {}

        @Override
        public List<AuditEntry<UUID>> loadAll() {
            return List.of();
        }

        @Override
        public Head head() {
            return null;
        }
    }

    private static final class InMemoryStore implements AuditStore<UUID> {

        @Override
        public Head nextSequenceAndPrevHash() {
            return new Head(1, CanonicalHasher.GENESIS_PREV_HASH);
        }

        @Override
        public void insert(AuditEntry<UUID> entry) {}

        @Override
        public void updateHead(String hash) {}

        @Override
        public List<AuditEntry<UUID>> loadAll() {
            return List.of();
        }

        @Override
        public Head head() {
            return new Head(0, CanonicalHasher.GENESIS_PREV_HASH);
        }
    }
}
