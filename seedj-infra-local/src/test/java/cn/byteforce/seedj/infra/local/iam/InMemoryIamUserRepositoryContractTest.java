package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepositoryContractTest;

/** 内存实现跑公共用户仓库契约套件。 */
class InMemoryIamUserRepositoryContractTest extends IamUserRepositoryContractTest {

    private final IamUserRepository repository = new InMemoryIamUserRepository();

    @Override
    protected IamUserRepository repository() {
        return repository;
    }
}
