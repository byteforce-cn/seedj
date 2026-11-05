package cn.byteforce.seedj.transit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TransitAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TransitAutoConfiguration.class));

    @Test
    @DisplayName("默认提供一个磁盘持久化的 TransitProvider")
    void providesTransitProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TransitProvider.class);
            assertThat(context.getBean(TransitProvider.class)).isInstanceOf(BcTransitProvider.class);
        });
    }

    @Test
    @DisplayName("seedj.transit.enabled=false 时不提供 bean")
    void disabled() {
        runner.withPropertyValues("seedj.transit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TransitProvider.class));
    }

    @Test
    @DisplayName("业务自定义 bean 优先")
    void userBeanWins() {
        runner.withBean(TransitProvider.class, () -> new TransitProvider() {
                    @Override
                    public TransitCrypto crypto() {
                        return null;
                    }

                    @Override
                    public TransitSigner signer() {
                        return null;
                    }

                    @Override
                    public TransitMac mac() {
                        return null;
                    }

                    @Override
                    public TransitKeyAdmin keyAdmin() {
                        return null;
                    }

                    @Override
                    public boolean supports(KeyType keyType, TransitOperation operation) {
                        return false;
                    }
                })
                .run(context -> assertThat(context.getBean(TransitProvider.class))
                        .isNotInstanceOf(BcTransitProvider.class));
    }

    @Test
    @DisplayName("seedj.transit.state-file 生效：两个上下文共享密钥")
    void stateFileIsShared(@org.junit.jupiter.api.io.TempDir Path dir) {
        String stateFile = dir.resolve("transit.properties").toString();

        runner.withPropertyValues("seedj.transit.state-file=" + stateFile).run(first -> {
            TransitProvider provider = first.getBean(TransitProvider.class);
            provider.keyAdmin().createKey(CreateKeyRequest.of("shared", KeyType.AES256_GCM96));

            runner.withPropertyValues("seedj.transit.state-file=" + stateFile).run(second ->
                    assertThat(second.getBean(TransitProvider.class).keyAdmin().readKey("shared"))
                            .isNotNull());
        });
    }
}
