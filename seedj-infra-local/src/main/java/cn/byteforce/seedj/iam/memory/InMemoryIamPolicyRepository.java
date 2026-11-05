package cn.byteforce.seedj.iam.memory;

import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe in-memory ABAC policy store. */
public class InMemoryIamPolicyRepository implements IamPolicyRepository {

    private final Map<String, IamPolicy> policies = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> epochs = new ConcurrentHashMap<>();

    @Override
    public IamPolicy save(IamPolicy policy) {
        IamPolicy current = policies.get(policy.id());
        if (current != null && current.version() != policy.version()) {
            throw new cn.byteforce.seedj.iam.IamException(
                    cn.byteforce.seedj.iam.IamErrorCode.CONFLICT,
                    "policy version conflict: expected " + current.version());
        }
        policies.put(policy.id(), policy);
        bumpEpoch(policy.tenantId());
        return policy;
    }

    @Override
    public Optional<IamPolicy> findById(String id) {
        return Optional.ofNullable(policies.get(id));
    }

    @Override
    public List<IamPolicy> findEnabled(String tenantId) {
        return policies.values().stream()
                .filter(policy -> policy.tenantId().equals(tenantId) && policy.enabled())
                .sorted(Comparator.comparingInt(IamPolicy::priority).reversed())
                .toList();
    }

    @Override
    public List<IamPolicy> findAll(String tenantId) {
        return policies.values().stream()
                .filter(policy -> policy.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(IamPolicy::name))
                .toList();
    }

    @Override
    public void delete(String id) {
        IamPolicy removed = policies.remove(id);
        if (removed != null) {
            bumpEpoch(removed.tenantId());
        }
    }

    @Override
    public long currentEpoch(String tenantId) {
        AtomicLong epoch = epochs.get(tenantId);
        return epoch == null ? 0 : epoch.get();
    }

    @Override
    public void bumpEpoch(String tenantId) {
        epochs.computeIfAbsent(tenantId, ignored -> new AtomicLong()).incrementAndGet();
    }
}
