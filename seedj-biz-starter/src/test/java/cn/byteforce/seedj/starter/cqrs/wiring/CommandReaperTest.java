package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandReaperTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final UUID ISSUER = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final String FINGERPRINT = "a".repeat(64);

    private static CommandRecord pending(String key, Instant leaseUntil) {
        return CommandRecord.pending(UUID.randomUUID(), "probe.command", NOW, ISSUER,
                Map.of(), key, FINGERPRINT, NOW, leaseUntil);
    }

    @Test
    void expiredPendingIsDeletedAndKeyIsReusable() {
        InMemoryCommandStore store = new InMemoryCommandStore();
        CommandRecord expired = pending("k-expired", NOW.minusSeconds(1));
        store.insert(expired);

        new CommandReaper(store, () -> NOW, null).reap();

        assertThat(store.findByIdempotencyKey(ISSUER, "k-expired")).isEmpty();
        assertThat(store.insert(pending("k-expired", NOW.plusSeconds(60)))).isTrue();
    }

    @Test
    void activePendingSurvives() {
        InMemoryCommandStore store = new InMemoryCommandStore();
        CommandRecord active = pending("k-active", NOW.plusSeconds(60));
        store.insert(active);

        new CommandReaper(store, () -> NOW, null).reap();

        assertThat(store.findByIdempotencyKey(ISSUER, "k-active")).contains(active);
    }

    @Test
    void completeFailsWhenPendingWasReaped() {
        InMemoryCommandStore store = new InMemoryCommandStore();
        CommandRecord expired = pending("k-reaped", NOW.minusSeconds(1));
        store.insert(expired);
        new CommandReaper(store, () -> NOW, null).reap();

        assertThatThrownBy(() -> store.complete(expired.accepted(UUID.randomUUID(), 1L,
                java.util.List.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reaperRunsOnStartup() {
        InMemoryCommandStore store = new InMemoryCommandStore();
        store.insert(pending("k-startup", NOW.minusSeconds(1)));

        new CommandReaper(store, () -> NOW, null).afterPropertiesSet();

        assertThat(store.findByIdempotencyKey(ISSUER, "k-startup")).isEmpty();
    }

    @Test
    void expiredCounterIsRecorded() {
        InMemoryCommandStore store = new InMemoryCommandStore();
        store.insert(pending("k-metric", NOW.minusSeconds(1)));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        new CommandReaper(store, () -> NOW, metrics).reap();

        assertThat(metrics.get("seedj.command.pending.expired").counter().count())
                .isEqualTo(1.0);
    }
}
