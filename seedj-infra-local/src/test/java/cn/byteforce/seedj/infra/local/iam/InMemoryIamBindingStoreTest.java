package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.authz.AttributeLookup;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.AttributeState;
import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存绑定/代理/属性源的时点语义。
 */
class InMemoryIamBindingStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final InMemoryIamBindingStore store = new InMemoryIamBindingStore();

    private final InMemoryIamAttributeSource attributes = new InMemoryIamAttributeSource();

    @BeforeEach
    void reset() {
        store.clear();
        attributes.clear();
    }

    @Test
    @DisplayName("valid_from 含、valid_to 不含")
    void bindingWindowIsHalfOpen() {
        store.save(binding("p", T0, T0.plusSeconds(3600)));

        assertThat(store.effectiveBindings("p", T0, null)).hasSize(1);
        assertThat(store.effectiveBindings("p", T0.plusSeconds(3600), null)).isEmpty();
    }

    @Test
    @DisplayName("recorded_as_of 过滤未记录或已封口的版本")
    void recordedAsOfSelectsVersion() {
        store.save(binding("p", T0, null, T0, T0.plusSeconds(600)));

        assertThat(store.effectiveBindings("p", T0.plusSeconds(120), T0.plusSeconds(300)))
                .hasSize(1);
        assertThat(store.effectiveBindings("p", T0.plusSeconds(120), T0.plusSeconds(600)))
                .isEmpty();
        assertThat(store.effectiveBindings("p", T0.plusSeconds(120), T0.minusSeconds(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("代理只在窗口内有效")
    void proxyWindowIsRespected() {
        store.save(new IamProxy("x", "away", "agent", Map.of("department", "D001"),
                T0, T0.plusSeconds(86400), T0, null));

        assertThat(store.effectiveProxies("agent", T0.plusSeconds(60), null)).hasSize(1);
        assertThat(store.effectiveProxies("agent", T0.plusSeconds(86400), null)).isEmpty();
    }

    @Test
    @DisplayName("属性快照选不晚于请求时点的最新一条")
    void attributesSelectLatestNotAfterAsOf() {
        attributes.save(snapshot("worker", "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z",
                Map.of("grade", "P5")));
        attributes.save(snapshot("worker", "2026-10-10T00:00:00Z", "2026-10-20T00:00:00Z",
                Map.of("grade", "M2")));

        AttributeLookup lookup = attributes.lookup("worker",
                Instant.parse("2026-10-15T12:00:00Z"), null);

        assertThat(lookup.state()).isEqualTo(AttributeState.FRESH);
        assertThat(lookup.snapshot().snapshot()).containsEntry("grade", "M2");
    }

    @Test
    @DisplayName("过期快照为 stale")
    void expiredSnapshotIsStale() {
        attributes.save(snapshot("worker", "2026-10-10T00:00:00Z", "2026-10-20T00:00:00Z",
                Map.of("grade", "M2")));

        assertThat(attributes.lookup("worker", Instant.parse("2026-10-20T00:00:00Z"), null)
                .state()).isEqualTo(AttributeState.STALE);
    }

    @Test
    @DisplayName("无快照为 missing")
    void missingSnapshotIsMissing() {
        assertThat(attributes.lookup("worker", Instant.parse("2026-08-01T00:00:00Z"), null)
                .state()).isEqualTo(AttributeState.MISSING);
    }

    @Test
    @DisplayName("属主不可达优先于任何快照")
    void unavailableSourceWins() {
        attributes.save(snapshot("outage", "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z",
                Map.of("grade", "P5")));
        attributes.markSourceUnavailable("outage");

        assertThat(attributes.lookup("outage", Instant.parse("2026-10-15T12:00:00Z"), null)
                .state()).isEqualTo(AttributeState.SOURCE_UNAVAILABLE);
    }

    private static IamBinding binding(String principal, Instant from, Instant to) {
        return binding(principal, from, to, T0, null);
    }

    private static IamBinding binding(String principal, Instant from, Instant to,
                                      Instant recordedAt, Instant supersededAt) {
        return new IamBinding("b-" + from + '-' + to, principal, "r1",
                Map.of("department", "D001"), from, to, recordedAt, supersededAt);
    }

    private static AttributeSnapshot snapshot(String principal, String asOf, String expiresAt,
                                              Map<String, Object> body) {
        return new AttributeSnapshot(principal, Instant.parse(asOf), Instant.parse(asOf),
                Instant.parse(expiresAt), "hr", "1.0.0", body);
    }
}
