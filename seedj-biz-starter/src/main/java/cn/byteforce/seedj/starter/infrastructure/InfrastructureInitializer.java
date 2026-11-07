package cn.byteforce.seedj.starter.infrastructure;

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
 * 启动期能力执行器：按 {@code getOrder()} 依次执行
 * 探针 → 初始化 → 校验。
 *
 * <p>必需能力失败时阻断启动；可选能力失败只记录 WARN。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class InfrastructureInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureInitializer.class);

    private final ObjectProvider<InfrastructureCapability> capabilities;

    public InfrastructureInitializer(ObjectProvider<InfrastructureCapability> capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public void afterPropertiesSet() {
        List<InfrastructureCapability> ordered = capabilities.orderedStream()
                .sorted(Comparator.comparingInt(InfrastructureCapability::getOrder))
                .toList();
        if (ordered.isEmpty()) {
            log.debug("no infrastructure capability registered");
            return;
        }
        log.info("found {} infrastructure capability component(s), probing...", ordered.size());
        for (InfrastructureCapability capability : ordered) {
            String name = capability.getName();
            try {
                if (capability.isReady()) {
                    log.debug("component [{}] already ready", name);
                    continue;
                }
                log.info("component [{}] is not ready, initializing...", name);
                capability.initialize();
                capability.validate();
                log.info("component [{}] initialized and validated", name);
            } catch (RuntimeException e) {
                if (capability.isRequired()) {
                    log.error("required infrastructure component [{}] failed, aborting startup",
                            name, e);
                    throw new InfrastructureException(
                            InfrastructureErrorCode.INITIALIZATION_FAILED,
                            "required infrastructure component [" + name + "] failed", e);
                }
                log.warn("optional infrastructure component [{}] failed: {}", name, e.getMessage());
            }
        }
        log.info("infrastructure initialization complete");
    }
}
