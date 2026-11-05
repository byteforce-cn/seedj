package cn.byteforce.seedj.iam.autoconfigure;

import cn.byteforce.seedj.iam.rbac.IamRbacAdminController;
import cn.byteforce.seedj.iam.security.IamJwkAdminController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** RBAC management API; the claim mapping itself is always active. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "seedj.iam.rbac", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import({IamRbacAdminController.class, IamJwkAdminController.class})
public class IamRbacConfiguration {
}
