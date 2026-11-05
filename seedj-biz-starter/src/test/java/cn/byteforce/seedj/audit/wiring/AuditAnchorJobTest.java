package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditAnchor;
import cn.byteforce.seedj.audit.AuditHead;
import cn.byteforce.seedj.audit.CanonicalHasher;
import cn.byteforce.seedj.audit.memory.InMemoryAuditAutoConfiguration;
import cn.byteforce.seedj.id.IdAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可选定时锚定的开关与调用语义。
 */
class AuditAnchorJobTest {

    private final List<AuditHead> anchored = new ArrayList<>();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AuditAutoConfiguration.class, InMemoryAuditAutoConfiguration.class,
                        IdAutoConfiguration.class))
                .withBean(PlatformTransactionManager.class, TestTransactionManager::new)
                .withBean(AuditAnchor.class, () -> anchored::add)
                .withPropertyValues("seedj.id.node-id=1", "seedj.audit.provider=memory");
    }

    @Test
    @DisplayName("默认不注册锚定任务")
    void jobIsAbsentByDefault() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditAnchorJob.class);
        });
    }

    @Test
    @DisplayName("开启后锚定当前链头")
    void jobCallsAnchorWithCurrentHead() {
        runner().withPropertyValues("seedj.audit.anchor.enabled=true",
                        "seedj.audit.anchor.initial-delay-millis=600000")
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuditAnchorJob.class);

            context.getBean(AuditAnchorJob.class).anchor();

            assertThat(anchored).hasSize(1);
            assertThat(anchored.get(0).getSequence()).isZero();
            assertThat(anchored.get(0).getHash()).isEqualTo(CanonicalHasher.GENESIS_PREV_HASH);
        });
    }

    @Test
    @DisplayName("锚定失败向上抛出，不静默吞掉")
    void anchorFailureIsNotSilent() {
        ApplicationContextRunner failing = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AuditAutoConfiguration.class, InMemoryAuditAutoConfiguration.class,
                        IdAutoConfiguration.class))
                .withBean(PlatformTransactionManager.class, TestTransactionManager::new)
                .withBean(AuditAnchor.class, () -> head -> {
                    throw new IllegalStateException("anchor down");
                })
                .withPropertyValues("seedj.id.node-id=1", "seedj.audit.provider=memory",
                        "seedj.audit.anchor.enabled=true",
                        "seedj.audit.anchor.initial-delay-millis=600000");

        failing.run(context -> {
            assertThat(context).hasNotFailed();
            AuditAnchorJob job = context.getBean(AuditAnchorJob.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(job::anchor)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("anchor down");
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
