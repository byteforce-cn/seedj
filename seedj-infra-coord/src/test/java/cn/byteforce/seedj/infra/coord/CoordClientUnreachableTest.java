package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import cn.byteforce.seedj.id.IdException;
import cn.byteforce.seedj.id.IdGenerator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * agent 不可达时必须快速失败，而不是挂起或静默降级。
 *
 * <p>客户端创建是懒连接：端口 1 上没有 agent 时装配仍然成功，失败发生在首次调用； 错误必须点名 agent 地址，否则运维只能对着一句 {@code UNAVAILABLE: io
 * exception} 猜配置。
 */
class CoordClientUnreachableTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(CoordAutoConfiguration.class));

    @Test
    void firstIdCallFailsFastAndNamesTheAgentAddress() {
        runner.withPropertyValues(
                        "seedj.coord.enabled=true",
                        "seedj.id.provider=coord",
                        "seedj.coord.agent-host=127.0.0.1",
                        "seedj.coord.agent-port=1",
                        "seedj.coord.request-timeout=200ms")
                .run(
                        context -> {
                            IdGenerator<String> generator = context.getBean(IdGenerator.class);

                            assertTimeoutPreemptively(
                                    Duration.ofSeconds(5),
                                    () ->
                                            assertThatThrownBy(generator::id)
                                                    .isInstanceOf(IdException.class)
                                                    .hasMessageContaining(
                                                            "coord idgen failed at 127.0.0.1:1"));
                        });
    }
}
