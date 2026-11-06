package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamPolicyRepositoryContractTest;

/** 内存实现跑公共ABAC 策略仓库契约套件。 */
class InMemoryIamPolicyRepositoryContractTest extends IamPolicyRepositoryContractTest {

    private final IamPolicyRepository repository = new InMemoryIamPolicyRepository();

    @Override
    protected IamPolicyRepository repository() {
        return repository;
    }
}
