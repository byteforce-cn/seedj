package cn.byteforce.seedj.starter.iam.identity;

import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Instant;

/**
 * Creates the first administrator exactly once. It never logs or returns the
 * configured password, and it only runs when both bootstrap fields are present.
 */
public class IamBootstrapRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IamBootstrapRunner.class);
    static final String ADMIN_ROLE = "iam-admin";
    static final String ADMIN_PERMISSION = "iam:admin";

    private final IamProperties.Admin properties;
    private final IamUserRepository users;
    private final IamAuthorizationRepository authorization;
    private final IamIdentityService identities;
    private final IdGenerator<String> idGenerator;

    public IamBootstrapRunner(IamProperties.Admin properties, IamUserRepository users,
                              IamAuthorizationRepository authorization,
                              IamIdentityService identities, IdGenerator<String> idGenerator) {
        this.properties = properties;
        this.users = users;
        this.authorization = authorization;
        this.identities = identities;
        this.idGenerator = idGenerator;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String username = properties.bootstrapUsername();
        if (username == null || username.isBlank()) {
            return;
        }
        if (properties.bootstrapPassword() == null || properties.bootstrapPassword().isBlank()) {
            throw new IllegalStateException(
                    "seedj.iam.admin.bootstrap-username is set but bootstrap-password is empty");
        }
        String tenantId = properties.tenantId();

        IamRole role = authorization.findRoleByCode(tenantId, ADMIN_ROLE).orElseGet(() ->
                authorization.createRole(IamRole.create(idGenerator.id(), tenantId, ADMIN_ROLE, "IAM administrator",
                        "Built-in IAM administrator", null, true)));
        authorization.savePermission(IamPermission.of(tenantId, "iam", "admin",
                "Manage IAM users, roles, policies, and MFA"));
        authorization.grantPermission(role.id(), ADMIN_PERMISSION);

        IamUser user = users.findByUserName(tenantId, username).orElse(null);
        if (user == null) {
            user = identities.createUser(tenantId, username, properties.bootstrapPassword(),
                    "IAM administrator", null);
            log.info("bootstrapped IAM administrator '{}'", username);
        }
        authorization.assignRoleToUser(user.id(), role.id(), Instant.now(), "bootstrap");
        authorization.bumpEpoch(tenantId);
    }
}
