package cn.byteforce.seedj.starter.coord.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoordConfigurationPropertiesRebinder} 语义测试（R3）：
 * {@code @ConfigurationProperties} 级 rebind 生效；{@code @Value} 不参与（T1 护栏）。
 */
class CoordConfigurationPropertiesRebinderTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoordConfigAutoConfiguration.class))
            .withUserConfiguration(TestBeans.class)
            .withPropertyValues("demo.refresh.value=1");

    @Test
    void rebindsConfigurationPropertiesFromUpdatedEnvironment() {
        runner.run(context -> {
            DemoProperties properties = context.getBean(DemoProperties.class);
            ValueHolder holder = context.getBean(ValueHolder.class);
            assertThat(properties.getValue()).isEqualTo("1");
            assertThat(holder.getValue()).isEqualTo("1");

            ConfigurableEnvironment environment = context.getEnvironment();
            environment.getPropertySources().addFirst(
                    new MapPropertySource("test-update", Map.of("demo.refresh.value", "2")));

            Map<String, Throwable> failures =
                    context.getBean(CoordConfigurationPropertiesRebinder.class).rebindAll();

            assertThat(failures).isEmpty();
            assertThat(context.getBean(DemoProperties.class).getValue()).isEqualTo("2");
            // T1 护栏：@Value 字段仅首次解析取值，不参与刷新（动态配置必须用 @ConfigurationProperties）
            assertThat(context.getBean(ValueHolder.class).getValue()).isEqualTo("1");
        });
    }

    @Test
    void rebindUnknownBeanReturnsFalse() {
        runner.run(context ->
                assertThat(context.getBean(CoordConfigurationPropertiesRebinder.class).rebind("no-such-bean"))
                        .isFalse());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @ConfigurationProperties(prefix = "demo.refresh")
        DemoProperties demoProperties() {
            return new DemoProperties();
        }

        @Bean
        ValueHolder valueHolder() {
            return new ValueHolder();
        }
    }

    /** 可绑定的配置属性（测试夹具，普通 final class，无 record）。 */
    public static class DemoProperties {

        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /** 携带 @Value 的 Bean：用于固定「@Value 不参与刷新」的契约行为。 */
    public static class ValueHolder {

        @Value("${demo.refresh.value}")
        private String value;

        public String getValue() {
            return value;
        }
    }
}
