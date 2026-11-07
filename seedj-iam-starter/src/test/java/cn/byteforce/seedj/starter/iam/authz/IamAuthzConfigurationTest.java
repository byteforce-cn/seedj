package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamBindingStore;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamBindingStore;
import cn.byteforce.seedj.starter.iam.IamProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 授权契约装配：存储 provider 推断、条件属性与关闭开关。 */
class IamAuthzConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IamAuthzConfiguration.class, BaseConfiguration.class);

    @Test
    @DisplayName("默认装配内存绑定存储与契约服务 bean")
    void defaultsToMemoryStore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IamBindingStore.class))
                    .isInstanceOf(InMemoryIamBindingStore.class);
            assertThat(context).hasSingleBean(IamContractAuthorizationService.class);
            assertThat(context).hasSingleBean(IamAuthorizationController.class);
            assertThat(context).hasSingleBean(CallerProvider.class);
            assertThat(context.getBean(SodRuleSet.class).rules()).isNotEmpty();
            assertThat(context.getBean(AuthzProperties.class).enabled()).isTrue();
        });
    }

    @Test
    @DisplayName("seedj.authz.provider=memory 显式选择内存实现")
    void explicitMemoryProvider() {
        runner.withPropertyValues("seedj.authz.provider=memory").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IamBindingStore.class))
                    .isInstanceOf(InMemoryIamBindingStore.class);
        });
    }

    @Test
    @DisplayName("JDBC store + JdbcTemplate 推断为 postgres 绑定存储")
    void jdbcStoreInfersPostgresProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(IamAuthzConfiguration.class, JdbcConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IamBindingStore.class))
                            .isInstanceOf(JdbcIamBindingStore.class);
                });
    }

    @Test
    @DisplayName("provider=postgres 但缺少 JdbcTemplate 时启动失败")
    void postgresWithoutJdbcTemplateFails() {
        runner.withPropertyValues("seedj.authz.provider=postgres").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires a DataSource/JdbcTemplate");
        });
    }

    @Test
    @DisplayName("未知 provider 启动失败")
    void unknownProviderFails() {
        runner.withPropertyValues("seedj.authz.provider=redis").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unknown seedj.authz.provider: redis");
        });
    }

    @Test
    @DisplayName("seedj.authz.enabled=false 关闭整个装配")
    void disabledSwitchBacksOff() {
        runner.withPropertyValues("seedj.authz.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IamBindingStore.class);
            assertThat(context).doesNotHaveBean(IamAuthorizationController.class);
        });
    }

    @Test
    @DisplayName("自定义绑定存储优先于默认装配")
    void customBindingStoreWins() {
        InMemoryIamBindingStore custom = new InMemoryIamBindingStore();

        runner.withBean(IamBindingStore.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IamBindingStore.class)).isSameAs(custom);
        });
    }

    @Test
    @DisplayName("AuthzProperties 归一化 enabled")
    void authzPropertiesNormalizesEnabled() {
        assertThat(new AuthzProperties(null, null).enabled()).isTrue();
        assertThat(new AuthzProperties(false, "memory").enabled()).isFalse();
        assertThat(AuthzProperties.defaults().enabled()).isTrue();
        assertThat(AuthzProperties.defaults().provider()).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    static class BaseConfiguration {

        @Bean
        IamProperties iamProperties() {
            return IamProperties.defaults();
        }

        @Bean
        IamAuthorizationRepository iamAuthorizationRepository() {
            InMemoryIamAuthorizationRepository repository =
                    new InMemoryIamAuthorizationRepository();
            repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "",
                    null, false));
            return repository;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JdbcConfiguration {

        @Bean
        IamProperties iamProperties() {
            return new IamProperties(null, null, "default", IamProperties.Store.JDBC, null,
                    null, null, null, null, null, null, null, null, null, null);
        }

        @Bean
        IamAuthorizationRepository iamAuthorizationRepository() {
            return new InMemoryIamAuthorizationRepository();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }
}
