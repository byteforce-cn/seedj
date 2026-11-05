package cn.byteforce.seedj.iam.memory;

import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.authz.IamProxy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存授权绑定/代理存储：只用于开发与测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryIamBindingStore implements IamBindingStore {

    private final List<IamBinding> bindings = new ArrayList<>();

    private final List<IamProxy> proxies = new ArrayList<>();

    @Override
    public synchronized void save(IamBinding binding) {
        bindings.add(binding);
    }

    @Override
    public synchronized void save(IamProxy proxy) {
        proxies.add(proxy);
    }

    @Override
    public synchronized List<IamBinding> effectiveBindings(String principal, Instant asOf,
                                                           Instant recordedAsOf) {
        return bindings.stream()
                .filter(binding -> binding.principal().equals(principal))
                .filter(binding -> binding.effectiveAt(asOf, recordedAsOf))
                .sorted(Comparator.comparing(IamBinding::validFrom))
                .toList();
    }

    @Override
    public synchronized List<IamProxy> effectiveProxies(String agent, Instant asOf,
                                                        Instant recordedAsOf) {
        return proxies.stream()
                .filter(proxy -> proxy.agent().equals(agent))
                .filter(proxy -> proxy.effectiveAt(asOf, recordedAsOf))
                .sorted(Comparator.comparing(IamProxy::validFrom))
                .toList();
    }

    /**
     * 清空全部数据（测试用）。
     */
    public synchronized void clear() {
        bindings.clear();
        proxies.clear();
    }
}
