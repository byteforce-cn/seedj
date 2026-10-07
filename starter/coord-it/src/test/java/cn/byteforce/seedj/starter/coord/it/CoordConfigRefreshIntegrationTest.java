package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordConfig;
import cn.byteforce.seedj.starter.coord.config.CoordConfigRefreshedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行期刷新集成验证（Phase 1.5，R3）：外部变更 → watch → 可变属性源 →
 * {@code @ConfigurationProperties} rebind → {@link CoordConfigRefreshedEvent}；
 * 并固化「{@code @Value} 不参与刷新」的契约行为（T1 护栏）。
 *
 * <p>时序要点：key 预置在静态初始化块完成（先于上下文装载）；引导阶段 Agent 地址
 * 经 {@link CoordItContainer} 的系统属性通道可见。</p>
 */
@SpringBootTest(properties = {
        "spring.config.import=coord:it.config.refresh",
        "spring.application.name=it-config-refresh"
})
class CoordConfigRefreshIntegrationTest extends AbstractCoordIntegrationTest {

    private static final String VALUE_KEY = "it.config.refresh.value";

    static {
        CoordItContainer container = CoordItContainer.getInstance();
        try (CoordClient client = CoordClient.create(CoordConfig.builder()
                .agentHost(container.agentHost())
                .agentPort(container.agentPort())
                .requestTimeout(Duration.ofSeconds(5))
                .build())) {
            client.configClient().put(VALUE_KEY, "seed");
        }
    }

    @Autowired
    private RefreshProperties properties;

    @Autowired
    private ValueHolder valueHolder;

    @Autowired
    private Environment environment;

    @Autowired
    private RefreshEventCollector collector;

    @Test
    void externalChangeRebindsConfigurationPropertiesButNotValueInjection() {
        assertThat(properties.getValue()).isEqualTo("seed");
        assertThat(valueHolder.getValue()).isEqualTo("seed");

        CoordItContainer container = CoordItContainer.getInstance();
        try (CoordClient writer = CoordClient.create(CoordConfig.builder()
                .agentHost(container.agentHost())
                .agentPort(container.agentPort())
                .requestTimeout(Duration.ofSeconds(5))
                .build())) {
            writer.configClient().put(VALUE_KEY, "updated");
        }

        awaitUntil("rebind 生效（@ConfigurationProperties 级）", Duration.ofSeconds(20),
                () -> "updated".equals(properties.getValue()));
        assertThat(environment.getProperty(VALUE_KEY)).isEqualTo("updated");
        awaitUntil("刷新事件发布", Duration.ofSeconds(10),
                () -> collector.changedKeys().contains(VALUE_KEY));
        // T1 护栏：@Value 字段仅首次解析取值，不参与刷新（动态配置必须用 @ConfigurationProperties）
        assertThat(valueHolder.getValue()).as("@Value 不参与刷新").isEqualTo("seed");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshProperties.class)
    static class RefreshConfiguration {

        @Bean
        ValueHolder valueHolder() {
            return new ValueHolder();
        }

        @Bean
        RefreshEventCollector refreshEventCollector() {
            return new RefreshEventCollector();
        }
    }

    /** 测试夹具：可绑定配置属性（普通 final class，无 record）。 */
    @ConfigurationProperties(prefix = "it.config.refresh")
    public static class RefreshProperties {

        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /** 携带 @Value 的 Bean：固化不参与刷新的行为。 */
    public static class ValueHolder {

        @Value("${it.config.refresh.value}")
        private String value;

        public String getValue() {
            return value;
        }
    }

    /** 刷新事件收集器（验证事件发布面）。 */
    public static class RefreshEventCollector {

        private final List<String> changedKeys = new CopyOnWriteArrayList<>();

        @EventListener
        public void onRefreshed(CoordConfigRefreshedEvent event) {
            changedKeys.addAll(event.getChangedKeys());
        }

        public List<String> changedKeys() {
            return List.copyOf(changedKeys);
        }
    }
}
