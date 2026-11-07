package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamPolicyRepositoryContractTest;
import org.junit.jupiter.api.BeforeEach;

/** Runs the shared policy repository contract against the PostgreSQL store. */
class JdbcIamPolicyRepositoryContractTest extends IamPolicyRepositoryContractTest {

    @BeforeEach
    void resetStore() {
        JdbcIamContractFixture.reset();
    }

    @Override
    protected IamPolicyRepository repository() {
        return new JdbcIamPolicyRepository(JdbcIamContractFixture.jdbc());
    }
}
