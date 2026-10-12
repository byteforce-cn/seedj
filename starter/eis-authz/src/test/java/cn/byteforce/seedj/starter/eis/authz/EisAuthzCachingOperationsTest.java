package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EisAuthzCachingOperations} 缓存语义测试（TTL / fail-open / 失效脉冲）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzCachingOperationsTest {

    private static final Instant AS_OF = Instant.parse("2026-10-15T12:00:00Z");

    /**
     * 计数委托。
     */
    private static final class CountingOperations implements EisAuthzOperations {

        private final AtomicInteger resolveCalls = new AtomicInteger();

        private final AtomicInteger checkCalls = new AtomicInteger();

        private final AtomicInteger conditionalCheckCalls = new AtomicInteger();

        @Override
        public EisAuthzResolution resolve(String principalRef, Instant asOf, Instant recordedAsOf,
                                          JsonNode scopeFilter) {
            resolveCalls.incrementAndGet();
            return new EisAuthzResolution(UUID.randomUUID(), asOf, List.of("r"), List.of("p"), List.of());
        }

        @Override
        public EisAuthzDecision check(String principalRef, String permission, Instant asOf,
                                      Instant recordedAsOf) {
            checkCalls.incrementAndGet();
            return new EisAuthzDecision(true, "允许", "ALLOWED", List.of());
        }

        @Override
        public EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf,
                                      List<EisAuthzAttributeCondition> conditions,
                                      EisAuthzAttributeSnapshot providedSnapshot) {
            conditionalCheckCalls.incrementAndGet();
            return new EisAuthzDecision(true, "允许", "ALLOWED", List.of(), EisAuthzAttributeState.FRESH);
        }
    }

    @Test
    void conditionalCheckBypassesCache() {
        CountingOperations delegate = new CountingOperations();
        EisAuthzCachingOperations caching = new EisAuthzCachingOperations(delegate, Duration.ofHours(1), true);
        List<EisAuthzAttributeCondition> conditions = List.of(EisAuthzAttributeCondition.employeeStatus("active"));

        caching.check("away", "p", AS_OF, null, conditions, null);
        caching.check("away", "p", AS_OF, null, conditions, null);

        assertThat(delegate.conditionalCheckCalls.get()).isEqualTo(2);
        assertThat(caching.cacheSize()).isZero();
    }

    @Test
    void repeatedCallsAreServedFromCacheWithinTtl() {
        CountingOperations delegate = new CountingOperations();
        EisAuthzCachingOperations caching = new EisAuthzCachingOperations(delegate, Duration.ofHours(1), true);

        caching.check("away", "p", AS_OF, null);
        caching.check("away", "p", AS_OF, null);
        caching.resolve("away", AS_OF, null, null);
        caching.resolve("away", AS_OF, null, null);

        assertThat(delegate.checkCalls.get()).isEqualTo(1);
        assertThat(delegate.resolveCalls.get()).isEqualTo(1);
        long[] stats = caching.stats();
        assertThat(stats[0]).isEqualTo(2); // hits
        assertThat(stats[1]).isEqualTo(2); // misses
    }

    @Test
    void differentKeysMissCache() {
        CountingOperations delegate = new CountingOperations();
        EisAuthzCachingOperations caching = new EisAuthzCachingOperations(delegate, Duration.ofHours(1), true);

        caching.check("away", "p", AS_OF, null);
        caching.check("away", "q", AS_OF, null);

        assertThat(delegate.checkCalls.get()).isEqualTo(2);
    }

    @Test
    void invalidateAllForcesReload() {
        CountingOperations delegate = new CountingOperations();
        EisAuthzCachingOperations caching = new EisAuthzCachingOperations(delegate, Duration.ofHours(1), true);

        caching.check("away", "p", AS_OF, null);
        caching.invalidateAll();
        caching.check("away", "p", AS_OF, null);

        assertThat(delegate.checkCalls.get()).isEqualTo(2);
        assertThat(caching.cacheSize()).isEqualTo(1);
    }

    @Test
    void disabledOrZeroTtlBypassesCache() {
        CountingOperations delegate = new CountingOperations();
        EisAuthzCachingOperations zeroTtl = new EisAuthzCachingOperations(delegate, Duration.ZERO, true);
        EisAuthzCachingOperations disabled = new EisAuthzCachingOperations(delegate, Duration.ofHours(1), false);

        zeroTtl.check("away", "p", AS_OF, null);
        zeroTtl.check("away", "p", AS_OF, null);
        disabled.check("away", "p", AS_OF, null);
        disabled.check("away", "p", AS_OF, null);

        assertThat(delegate.checkCalls.get()).isEqualTo(4);
        assertThat(zeroTtl.cacheSize()).isZero();
        assertThat(zeroTtl.keys().hasNext()).isFalse();
    }
}
