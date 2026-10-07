package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期配置导入集成验证（Phase 1，C2）：{@code spring.config.import=coord:<prefix>}
 * → 引导快照进入 Environment → {@code @ConfigurationProperties} 绑定；
 * 前缀之外（以及未导入）的 key 不可见。
 *
 * <p>时序要点：引导阶段（ConfigData）早于 {@code @DynamicPropertySource} 生效时机，
 * 因此 Agent 地址经 {@link CoordItContainer} 写入系统属性；且 key 预置（seed）必须
 * 在静态初始化块完成（类初始化先于上下文装载）。</p>
 */
@SpringBootTest(properties = {
        "spring.config.import=coord:it.config.import",
        "spring.application.name=it-config-import"
})
class CoordConfigImportIntegrationTest extends AbstractCoordIntegrationTest {

    private static final String NAME_KEY = "it.config.import.name";

    static {
        CoordItContainer container = CoordItContainer.getInstance();
        try (CoordClient client = CoordClient.create(CoordConfig.builder()
                .agentHost(container.agentHost())
                .agentPort(container.agentPort())
                .requestTimeout(Duration.ofSeconds(5))
                .build())) {
            client.configClient().put(NAME_KEY, "coord-it");
            // 前缀之外的 key：不应被导入暴露
            client.configClient().put("it.config.outside", "invisible");
        }
    }

    @Autowired
    private Environment environment;

    @Autowired
    private ImportedProperties properties;

    @Test
    void importsSnapshotAndBindsTypedProperties() {
        assertThat(environment.getProperty(NAME_KEY)).isEqualTo("coord-it");
        assertThat(properties.getName()).isEqualTo("coord-it");
        assertThat(environment.getProperty("it.config.outside"))
                .as("导入前缀仅作过滤：前缀外 key 不可见")
                .isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImportedProperties.class)
    static class PropsConfiguration {
    }

    /** 测试夹具（普通 final class，无 record）。 */
    @ConfigurationProperties(prefix = "it.config.import")
    public static class ImportedProperties {

        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
