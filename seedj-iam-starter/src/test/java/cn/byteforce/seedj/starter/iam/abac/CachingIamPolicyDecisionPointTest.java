package cn.byteforce.seedj.starter.iam.abac;

import cn.byteforce.seedj.security.AuthorizationSubject;
import cn.byteforce.seedj.security.AuthorizationRequest;
import cn.byteforce.seedj.security.AuthorizationDecision;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamPolicyRepository;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 决策缓存：按 epoch 失效、TTL 兜底、空主体直通、上限清理。 */
class CachingIamPolicyDecisionPointTest {

    private final InMemoryIamPolicyRepository policies = new InMemoryIamPolicyRepository();
    private final CountingPdp delegate = new CountingPdp();

    @Test
    @DisplayName("同一请求在 TTL 内只询问一次委托方")
    void cachesWithinTtl() {
        CachingIamPolicyDecisionPoint pdp =
                new CachingIamPolicyDecisionPoint(delegate, policies, Duration.ofMinutes(1));

        assertThat(pdp.decide(request("u-1", "o-1")).allowed()).isTrue();
        assertThat(pdp.decide(request("u-1", "o-1")).allowed()).isTrue();
        assertThat(pdp.decide(request("u-1", "o-2")).allowed()).isTrue();

        assertThat(delegate.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("epoch 变化使缓存失效")
    void epochBumpInvalidatesCache() {
        CachingIamPolicyDecisionPoint pdp =
                new CachingIamPolicyDecisionPoint(delegate, policies, Duration.ofMinutes(1));

        pdp.decide(request("u-1", "o-1"));
        policies.bumpEpoch("default");
        pdp.decide(request("u-1", "o-1"));

        assertThat(delegate.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("空 userId 不缓存")
    void blankUserIdBypassesCache() {
        CachingIamPolicyDecisionPoint pdp =
                new CachingIamPolicyDecisionPoint(delegate, policies, Duration.ofMinutes(1));

        pdp.decide(request("", "o-1"));
        pdp.decide(request("", "o-1"));

        assertThat(delegate.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("TTL 为 null/0/负数时回落 30 秒默认值")
    void invalidTtlFallsBackToDefault() {
        for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-5)}) {
            CountingPdp counting = new CountingPdp();
            CachingIamPolicyDecisionPoint pdp =
                    new CachingIamPolicyDecisionPoint(counting, policies, ttl);

            pdp.decide(request("u-1", "o-1"));
            pdp.decide(request("u-1", "o-1"));

            assertThat(counting.calls.get()).as("ttl=%s 仍应命中缓存", ttl).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("缓存超过 10000 条后整体清理")
    void clearsCacheAboveCap() {
        CachingIamPolicyDecisionPoint pdp =
                new CachingIamPolicyDecisionPoint(delegate, policies, Duration.ofMinutes(1));

        for (int index = 0; index <= 10_000; index++) {
            pdp.decide(request("u-1", "o-" + index));
        }
        pdp.decide(request("u-1", "o-fresh"));
        int afterClear = delegate.calls.get();
        pdp.decide(request("u-1", "o-0"));

        assertThat(afterClear).isEqualTo(10_002);
        assertThat(delegate.calls.get()).isEqualTo(afterClear + 1);
    }

    private static AuthorizationRequest request(String userId, String resourceId) {
        AuthorizationSubject subject =
                new AuthorizationSubject(
                        userId,
                        "alice",
                        "default",
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of("pwd"),
                        "urn:seedj:acr:pwd",
                        Map.of());
        return new AuthorizationRequest(
                subject,
                "read",
                "order",
                resourceId,
                Map.of(),
                java.time.Instant.parse("2026-11-21T00:00:00Z"),
                Map.of());
    }

    private static final class CountingPdp implements IamPolicyDecisionPoint {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AuthorizationDecision decide(AuthorizationRequest request) {
            calls.incrementAndGet();
            return AuthorizationDecision.allow("allow-1");
        }
    }
}
