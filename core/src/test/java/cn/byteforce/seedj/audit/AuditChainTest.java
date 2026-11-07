package cn.byteforce.seedj.audit;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.audit.spi.AuditStore;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.id.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditChainTest {

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");
    private static final UUID RESOURCE = UUID.fromString("018f2e3a-9999-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00.123456789Z");

    @Test
    void appendUsesInjectedIdGeneratorAndVerifiesChain() {
        FakeAuditStore store = new FakeAuditStore();
        AtomicLong idSequence = new AtomicLong();
        IdGenerator<UUID> ids = () -> new UUID(0, idSequence.incrementAndGet());
        Clock clock = () -> NOW;
        List<AuditHead> anchored = new ArrayList<>();
        AuditChain<UUID> chain = new AuditChain<>(store, clock, anchored::add, ids);

        chain.append(
                new AuditAppendRequest<>(
                        "voucher.create",
                        ACTOR,
                        "voucher",
                        RESOURCE,
                        JsonMapper.builder().build().readTree("{\"amount\":\"100.00\"}")));

        assertThat(store.entries).hasSize(1);
        AuditEntry<UUID> entry = store.entries.get(0);
        assertThat(entry.getId()).isEqualTo(new UUID(0, 1));
        assertThat(entry.getSequence()).isEqualTo(1);
        assertThat(entry.getTimestamp()).isEqualTo(Instant.parse("2026-10-08T09:00:00.123456Z"));
        assertThat(entry.getHash()).isNotBlank();
        assertThat(chain.verify()).isEmpty();

        AuditHead head = chain.head();
        assertThat(head.getSequence()).isEqualTo(1);
        assertThat(head.getHash()).isEqualTo(entry.getHash());

        AuditHead anchoredHead = chain.anchorNow();
        assertThat(anchoredHead.getHash()).isEqualTo(entry.getHash());
        assertThat(anchored).containsExactly(anchoredHead);
    }

    @Test
    void verifierReportsSequenceGapAndTamperedHash() {
        FakeAuditStore store = new FakeAuditStore();
        AtomicLong idSequence = new AtomicLong();
        AuditChain<UUID> chain =
                new AuditChain<>(
                        store,
                        () -> NOW,
                        head -> {},
                        () -> new UUID(0, idSequence.incrementAndGet()));
        JsonMapper mapper = JsonMapper.builder().build();
        chain.append(new AuditAppendRequest<>("a", ACTOR, "t", null, mapper.createObjectNode()));
        chain.append(new AuditAppendRequest<>("b", ACTOR, "t", null, mapper.createObjectNode()));

        assertThat(chain.verify()).isEmpty();

        AuditEntry<UUID> tampered =
                new AuditEntry<>(
                        store.entries.get(0).getId(),
                        5,
                        store.entries.get(0).getTimestamp(),
                        ACTOR,
                        "a",
                        "t",
                        null,
                        mapper.createObjectNode(),
                        "deadbeef",
                        CanonicalHasher.GENESIS_PREV_HASH,
                        null);
        store.entries.set(0, tampered);

        assertThat(chain.verify())
                .extracting(AuditVerifier.AuditViolation::getType)
                .contains("sequence_gap", "invalid_hash");
    }

    private static final class FakeAuditStore implements AuditStore<UUID> {

        private long sequence;
        private String headHash = CanonicalHasher.GENESIS_PREV_HASH;
        private final List<AuditEntry<UUID>> entries = new ArrayList<>();

        @Override
        public Head nextSequenceAndPrevHash() {
            return new Head(++sequence, headHash);
        }

        @Override
        public void insert(AuditEntry<UUID> entry) {
            entries.add(entry);
        }

        @Override
        public void updateHead(String hash) {
            headHash = hash;
        }

        @Override
        public List<AuditEntry<UUID>> loadAll() {
            return List.copyOf(entries);
        }

        @Override
        public Head head() {
            return new Head(sequence, headHash);
        }
    }
}
