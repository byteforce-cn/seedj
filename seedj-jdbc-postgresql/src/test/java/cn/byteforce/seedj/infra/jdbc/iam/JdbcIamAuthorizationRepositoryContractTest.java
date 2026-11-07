package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepositoryContractTest;
import org.junit.jupiter.api.BeforeEach;

/**
 * Runs the shared authorization repository contract against the PostgreSQL store. Role inheritance
 * and bindings are foreign-keyed, so the contract's referenced ids are seeded as anchors.
 */
class JdbcIamAuthorizationRepositoryContractTest extends IamAuthorizationRepositoryContractTest {

    @BeforeEach
    void resetStore() {
        JdbcIamContractFixture.reset();
        JdbcIamContractFixture.seedUser("u1");
        JdbcIamContractFixture.seedGroup("g1");
        JdbcIamContractFixture.seedRole("r0");
    }

    @Override
    protected IamAuthorizationRepository repository() {
        return new JdbcIamAuthorizationRepository(JdbcIamContractFixture.jdbc());
    }
}
