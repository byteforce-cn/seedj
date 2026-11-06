package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.iam.spi.IamTotpRepositoryContractTest;

/** 内存实现跑公共TOTP/MFA 仓库契约套件。 */
class InMemoryIamTotpRepositoryContractTest extends IamTotpRepositoryContractTest {

    private final IamTotpRepository repository = new InMemoryIamTotpRepository();

    @Override
    protected IamTotpRepository repository() {
        return repository;
    }
}
