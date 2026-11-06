package cn.byteforce.seedj.infra.local.pki;

import cn.byteforce.seedj.pki.PkiProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PkiAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PkiAutoConfiguration.class));

    private String[] properties(Path dir) {
        return new String[]{
                "seedj.pki.revocation-store=" + dir.resolve("pki-state.properties"),
                "seedj.pki.ca-resource=classpath:certs/pki-test-ca.p12",
                "seedj.pki.ca-password=test-password",
                "seedj.pki.ca-alias=pki-test-ca"
        };
    }

    @Test
    @DisplayName("默认提供 PkiProvider")
    void providesProvider(@TempDir Path dir) {
        runner.withPropertyValues(properties(dir))
                .run(context -> assertThat(context).hasSingleBean(PkiProvider.class));
    }

    @Test
    @DisplayName("seedj.pki.enabled=false 时不提供 bean")
    void disabled(@TempDir Path dir) {
        runner.withPropertyValues(properties(dir))
                .withPropertyValues("seedj.pki.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PkiProvider.class));
    }

    @Test
    @DisplayName("CA 资源缺失时启动失败（fail fast，不静默降级）")
    void missingCaFailsFast(@TempDir Path dir) {
        runner.withPropertyValues(
                        "seedj.pki.revocation-store=" + dir.resolve("s.properties"),
                        "seedj.pki.ca-resource=classpath:no-such-ca.p12")
                .run(context -> assertThat(context).hasFailed());
    }
}
