package cn.byteforce.seedj.starter.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegradedModeGuardTest {

    @Test
    void degradedModeFailsOutsideLocalAndTest() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("seedj.command.degraded-mode", "true")
                .withProperty("spring.profiles.active", "staging");

        assertThatThrownBy(() -> new DegradedModeGuard(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("degraded-mode");
    }

    @Test
    void devAuthFailsInStaging() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("seedj.biz.dev-auth.enabled", "true")
                .withProperty("spring.profiles.active", "staging");

        assertThatThrownBy(() -> new DegradedModeGuard(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-auth");
    }

    @Test
    void localAndTestAreAllowed() {
        assertThatCode(() -> new DegradedModeGuard(new MockEnvironment()
                .withProperty("seedj.command.degraded-mode", "true")
                .withProperty("spring.profiles.active", "local")).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> new DegradedModeGuard(new MockEnvironment()
                .withProperty("seedj.command.degraded-mode", "true")
                .withProperty("spring.profiles.active", "test")).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void allowedProfilesAreConfigurable() {
        assertThatCode(() -> new DegradedModeGuard(new MockEnvironment()
                .withProperty("seedj.command.degraded-mode", "true")
                .withProperty("spring.profiles.active", "staging")
                .withProperty("seedj.degraded.allowed-profiles", "local,test,staging"))
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void noDegradedFlagIsAlwaysAllowed() {
        assertThatCode(() -> new DegradedModeGuard(new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
