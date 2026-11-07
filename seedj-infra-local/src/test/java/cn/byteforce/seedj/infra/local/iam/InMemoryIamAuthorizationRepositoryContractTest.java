package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepositoryContractTest;

/** 内存实现跑公共RBAC 仓库契约套件。 */
class InMemoryIamAuthorizationRepositoryContractTest
        extends IamAuthorizationRepositoryContractTest {

    private final IamAuthorizationRepository repository = new InMemoryIamAuthorizationRepository();

    @Override
    protected IamAuthorizationRepository repository() {
        return repository;
    }
}
