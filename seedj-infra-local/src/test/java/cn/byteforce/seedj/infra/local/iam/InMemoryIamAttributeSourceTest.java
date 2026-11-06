package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.authz.AttributeLookup;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.AttributeState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 内存属性源：按业务时点与记录时点选取最新快照，并区分缺失/不可达。 */
class InMemoryIamAttributeSourceTest {

    private static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");

    private final InMemoryIamAttributeSource source = new InMemoryIamAttributeSource();

    @Test
    void missingSnapshotIsReported() {
        AttributeLookup lookup = source.lookup("alice", T0, null);

        assertThat(lookup.state()).isEqualTo(AttributeState.MISSING);
        assertThat(lookup.snapshot()).isNull();
    }

    @Test
    void unavailableSourceWinsOverSnapshots() {
        source.save(snapshot("alice", T0, null, Map.of("department", "Eng")));
        source.markSourceUnavailable("alice");

        AttributeLookup lookup = source.lookup("alice", T0, null);

        assertThat(lookup.state()).isEqualTo(AttributeState.SOURCE_UNAVAILABLE);
        assertThat(lookup.snapshot()).isNull();
    }

    @Test
    void selectsNewestSnapshotAtOrBeforeRequestTime() {
        source.save(snapshot("alice", T0, null, Map.of("department", "Eng")));
        source.save(snapshot("alice", T0.plusSeconds(60), null, Map.of("department", "Ops")));

        AttributeLookup lookup = source.lookup("alice", T0.plusSeconds(30), null);

        assertThat(lookup.state()).isEqualTo(AttributeState.FRESH);
        assertThat(lookup.snapshot().snapshot()).containsEntry("department", "Eng");
    }

    @Test
    void ignoresSnapshotsRecordedAfterRecordedTime() {
        source.save(new AttributeSnapshot("alice", T0, T0.plusSeconds(120), null, "hr", "v1",
                Map.of("department", "Ops")));
        source.save(new AttributeSnapshot("alice", T0, T0, null, "hr", "v1",
                Map.of("department", "Eng")));

        AttributeLookup lookup = source.lookup("alice", T0, T0.plusSeconds(30));

        assertThat(lookup.snapshot().snapshot()).containsEntry("department", "Eng");
    }

    @Test
    void expiredSnapshotIsStale() {
        source.save(new AttributeSnapshot("alice", T0, T0, T0.plusSeconds(60), "hr", "v1",
                Map.of("department", "Eng")));

        assertThat(source.lookup("alice", T0.plusSeconds(120), null).state())
                .isEqualTo(AttributeState.STALE);
        assertThat(source.lookup("alice", T0.plusSeconds(30), null).state())
                .isEqualTo(AttributeState.FRESH);
    }

    @Test
    void ignoresOtherPrincipalsAndCanBeCleared() {
        source.save(snapshot("bob", T0, null, Map.of("department", "Eng")));

        assertThat(source.lookup("alice", T0, null).state()).isEqualTo(AttributeState.MISSING);

        source.clear();

        assertThat(source.lookup("bob", T0, null).state()).isEqualTo(AttributeState.MISSING);
    }

    private static AttributeSnapshot snapshot(String principal, Instant asOf, Instant expiresAt,
                                              Map<String, Object> attributes) {
        return new AttributeSnapshot(principal, asOf, asOf, expiresAt, "hr", "v1", attributes);
    }
}
