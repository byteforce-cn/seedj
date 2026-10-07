package cn.byteforce.seedj.starter.data.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MybatisBaselineAutoConfiguration} 装配与开关契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class MybatisBaselineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisBaselineAutoConfiguration.class));

    @Test
    void defaultsRegisterAuditAndPaginationOnly() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MetaObjectHandler.class);
            assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);

            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            assertThat(interceptor.getInterceptors())
                    .anyMatch(PaginationInnerInterceptor.class::isInstance)
                    .noneMatch(TenantLineInnerInterceptor.class::isInstance);
        });
    }

    @Test
    void tenantPluginCanBeEnabled() {
        runner.withPropertyValues(
                        "byteforce.mybatis.tenant.enabled=true",
                        "byteforce.mybatis.tenant.ignore-tables[0]=sys_dict")
                .run(context -> {
                    MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
                    assertThat(interceptor.getInterceptors())
                            .anyMatch(TenantLineInnerInterceptor.class::isInstance);
                    // 租户插件必须先于分页插件执行
                    assertThat(interceptor.getInterceptors().get(0))
                            .isInstanceOf(TenantLineInnerInterceptor.class);
                });
    }

    @Test
    void auditCanBeDisabled() {
        runner.withPropertyValues("byteforce.mybatis.audit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MetaObjectHandler.class);
                    assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
                });
    }

    @Test
    void killSwitchDisablesBaseline() {
        runner.withPropertyValues("byteforce.mybatis.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MetaObjectHandler.class);
                    assertThat(context).doesNotHaveBean(MybatisPlusInterceptor.class);
                });
    }
}
