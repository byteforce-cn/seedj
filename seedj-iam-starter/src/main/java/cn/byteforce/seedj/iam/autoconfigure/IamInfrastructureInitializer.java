package cn.byteforce.seedj.iam.autoconfigure;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Comparator;
import java.util.List;

/**
 * IAM starter's copy of the capability lifecycle so an application that depends
 * only on {@code seedj-iam-starter} still runs IAM/OAuth2 migrations. The
 * aggregate starter keeps its own initializer; whichever is present runs the
 * idempotent migrations.
 */
public class IamInfrastructureInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IamInfrastructureInitializer.class);

    private final ObjectProvider<InfrastructureCapability> capabilities;

    public IamInfrastructureInitializer(ObjectProvider<InfrastructureCapability> capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public void afterPropertiesSet() {
        List<InfrastructureCapability> sorted = capabilities.orderedStream()
                .sorted(Comparator.comparingInt(InfrastructureCapability::getOrder))
                .toList();
        for (InfrastructureCapability capability : sorted) {
            String name = capability.getName();
            try {
                if (capability.isReady()) {
                    continue;
                }
                log.info("component [{}] is not ready, initializing...", name);
                capability.initialize();
                capability.validate();
            } catch (RuntimeException e) {
                if (capability.isRequired()) {
                    throw new InfrastructureException(InfrastructureErrorCode.INITIALIZATION_FAILED,
                            "infrastructure initialization failed: " + name, e);
                }
                log.warn("optional infrastructure component [{}] failed: {}", name, e.getMessage());
            }
        }
    }
}
