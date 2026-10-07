package cn.byteforce.seedj.starter.coord.config.bootstrap;

import cn.byteforce.seedj.starter.coord.CoordProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link CoordConfigDataLoader} 失败语义测试（C5）：
 * 可用性错误 + 非 optional = 启动失败；+ optional = WARN 跳过。
 *
 * <p>使用不可达端口（1）构造可用性错误，不依赖 Docker / 真实 Agent。</p>
 */
class CoordConfigDataLoaderTest {

    private final CoordConfigDataLoader loader = new CoordConfigDataLoader();

    private static CoordConfigDataResource resource(boolean optional) {
        CoordProperties properties = new CoordProperties();
        properties.setAgentHost("127.0.0.1");
        properties.setAgentPort(1);
        properties.setRequestTimeout(Duration.ofMillis(300));
        return new CoordConfigDataResource(List.of("it.config.probe"), properties, optional);
    }

    @Test
    void availabilityFailureFailsFastWhenRequired() {
        assertThatThrownBy(() -> loader.load(mock(org.springframework.boot.context.config.ConfigDataLoaderContext.class),
                resource(false)))
                .isInstanceOf(ConfigDataResourceNotFoundException.class)
                .hasMessageContaining("it.config.probe");
    }

    @Test
    void availabilityFailureIsSkippedWhenOptional() throws Exception {
        ConfigData data = loader.load(mock(org.springframework.boot.context.config.ConfigDataLoaderContext.class),
                resource(true));

        assertThat(data.getPropertySources()).isEmpty();
        assertThat(data).isSameAs(ConfigData.EMPTY);
    }
}
