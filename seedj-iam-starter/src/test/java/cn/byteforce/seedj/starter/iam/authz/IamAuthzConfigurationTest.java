package cn.byteforce.seedj.starter.iam.authz;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.authz.IamAttributeSource;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAttributeSource;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamBindingStore;
import cn.byteforce.seedj.starter.iam.IamProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 授权契约装配：只消费适配器提供的绑定存储与属性源。 */
class IamAuthzConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(IamAuthzConfiguration.class, BaseConfiguration.class);

    @Test
    @DisplayName("默认装配契约服务、决策点与 /check 控制器")
    void wiresTheContractServices() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IamContractAuthorizationService.class);
                    assertThat(context).hasSingleBean(IamAuthorizationController.class);
                    assertThat(context).hasSingleBean(CallerProvider.class);
                    assertThat(context.getBean(SodRuleSet.class).rules()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("seedj.authz.enabled=false 关闭整个装配")
    void disabledSwitchBacksOff() {
        runner.withPropertyValues("seedj.authz.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(IamContractAuthorizationService.class);
                            assertThat(context).doesNotHaveBean(IamAuthorizationController.class);
                        });
    }

    @Test
    @DisplayName("缺少绑定存储时不静默降级：启动期直接失败")
    void missingBindingStoreFailsFast() {
        new ApplicationContextRunner()
                .withUserConfiguration(IamAuthzConfiguration.class, IamPropertiesConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .hasMessageContaining("IamBindingStore");
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class IamPropertiesConfiguration {

        @Bean
        IamProperties iamProperties() {
            return IamProperties.defaults();
        }

        @Bean
        IamAuthorizationRepository iamAuthorizationRepository() {
            return new InMemoryIamAuthorizationRepository();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BaseConfiguration {

        @Bean
        IamProperties iamProperties() {
            return IamProperties.defaults();
        }

        @Bean
        IamBindingStore defaultBindingStore() {
            return new InMemoryIamBindingStore();
        }

        @Bean
        IamAttributeSource iamAttributeSource() {
            return new InMemoryIamAttributeSource();
        }

        @Bean
        IamAuthorizationRepository iamAuthorizationRepository() {
            InMemoryIamAuthorizationRepository repository =
                    new InMemoryIamAuthorizationRepository();
            repository.createRole(
                    IamRole.create("r-1", "default", "approver", "审批人", "", null, false));
            return repository;
        }
    }
}
