package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepositoryContractTest;

/** 内存实现跑公共组仓库契约套件。 */
class InMemoryIamGroupRepositoryContractTest extends IamGroupRepositoryContractTest {

    private final IamGroupRepository repository = new InMemoryIamGroupRepository();

    @Override
    protected IamGroupRepository repository() {
        return repository;
    }
}
