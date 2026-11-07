package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Outbox 自己的能力生命周期，让只依赖 {@code seedj-jdbc-postgresql} 的应用 也能执行迁移。宿主自己的 initializer 与其共存，先到者执行幂等迁移。
 */
public class OutboxInfrastructureInitializer implements InitializingBean {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxInfrastructureInitializer.class);

    private final ObjectProvider<InfrastructureCapability> capabilities;

    public OutboxInfrastructureInitializer(ObjectProvider<InfrastructureCapability> capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public void afterPropertiesSet() {
        List<InfrastructureCapability> sorted =
                capabilities
                        .orderedStream()
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
                    throw new InfrastructureException(
                            InfrastructureErrorCode.INITIALIZATION_FAILED,
                            "infrastructure initialization failed: " + name,
                            e);
                }
                log.warn("optional infrastructure component [{}] failed: {}", name, e.getMessage());
            }
        }
    }
}
