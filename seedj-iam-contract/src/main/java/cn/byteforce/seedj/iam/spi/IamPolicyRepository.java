package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamPolicy;
import java.util.List;
import java.util.Optional;

/** ABAC policy store + cross-instance authorization epoch. */
public interface IamPolicyRepository {

    IamPolicy save(IamPolicy policy);

    Optional<IamPolicy> findById(String id);

    List<IamPolicy> findEnabled(String tenantId);

    List<IamPolicy> findAll(String tenantId);

    void delete(String id);

    long currentEpoch(String tenantId);

    void bumpEpoch(String tenantId);
}
