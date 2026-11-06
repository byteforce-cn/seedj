package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditException;
import cn.byteforce.seedj.infra.local.audit.InMemoryAuditAutoConfiguration;
import cn.byteforce.seedj.audit.spi.AuditStore;
import cn.byteforce.seedj.cqrs.CommandAudit;
import cn.byteforce.seedj.infra.local.id.IdAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计装配的“启动即失败”契约。
 */
class AuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AuditAutoConfiguration.class, InMemoryAuditAutoConfiguration.class,
                    IdAutoConfiguration.class))
            .withBean(PlatformTransactionManager.class, TestTransactionManager::new);

    @Test
    @DisplayName("enabled=true 但没有 AuditStore：上下文启动失败")
    void missingStoreFailsStartup() {
        runner.withPropertyValues("seedj.id.node-id=1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(AuditException.class)
                    .rootCause()
                    .hasMessageContaining("no AuditStore");
        });
    }

    @Test
    @DisplayName("provider=memory：审计链装配成功")
    void memoryProviderWiresAuditChain() {
        runner.withPropertyValues("seedj.id.node-id=1", "seedj.audit.provider=memory")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditStore.class);
                    assertThat(context).hasSingleBean(CommandAudit.class);
                });
    }

    @Test
    @DisplayName("enabled=false：审计装配整体退让")
    void disabledSkipsAudit() {
        runner.withPropertyValues("seedj.id.node-id=1", "seedj.audit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CommandAudit.class);
                });
    }

    static final class TestTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
