package cn.byteforce.seedj.starter.iam.abac;

import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Short-TTL decision cache keyed by the cross-instance authorization epoch. */
public class CachingIamPolicyDecisionPoint implements IamPolicyDecisionPoint {

    private record Cached(IamAuthorizationDecision decision, Instant expiresAt) {}

    private final IamPolicyDecisionPoint delegate;
    private final IamPolicyRepository policies;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public CachingIamPolicyDecisionPoint(
            IamPolicyDecisionPoint delegate, IamPolicyRepository policies, Duration ttl) {
        this.delegate = delegate;
        this.policies = policies;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(30) : ttl;
    }

    @Override
    public IamAuthorizationDecision decide(IamAuthorizationRequest request) {
        if (request.subject().userId().isBlank()) {
            return delegate.decide(request);
        }
        long epoch = policies.currentEpoch(request.subject().tenantId());
        String key =
                request.subject().tenantId()
                        + "|"
                        + request.subject().userId()
                        + "|"
                        + epoch
                        + "|"
                        + request.action()
                        + "|"
                        + request.resourceType()
                        + "|"
                        + request.resourceId();
        Instant now = Instant.now();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.decision();
        }
        IamAuthorizationDecision decision = delegate.decide(request);
        if (cache.size() > 10_000) {
            cache.clear();
        }
        cache.put(key, new Cached(decision, now.plus(ttl)));
        return decision;
    }
}
