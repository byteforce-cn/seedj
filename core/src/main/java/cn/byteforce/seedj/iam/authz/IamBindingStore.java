package cn.byteforce.seedj.iam.authz;

import java.time.Instant;
import java.util.List;

/**
 * 授权绑定/代理存储端口。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface IamBindingStore {

    void save(IamBinding binding);

    void save(IamProxy proxy);

    List<IamBinding> effectiveBindings(String principal, Instant asOf, Instant recordedAsOf);

    List<IamProxy> effectiveProxies(String agent, Instant asOf, Instant recordedAsOf);
}
