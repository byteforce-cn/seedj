package cn.byteforce.seedj.starter.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 准入配置的默认值与正数校验。 */
class AdmissionPropertiesTest {

    @Test
    @DisplayName("默认开启，ttl 5m，sweep 60s")
    void defaults() {
        AdmissionProperties properties = new AdmissionProperties(true, null, null);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.sweep()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("ttl/sweep 非正：拒绝")
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> new AdmissionProperties(true, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedj.ai.guard.admission.ttl");
        assertThatThrownBy(() -> new AdmissionProperties(true, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedj.ai.guard.admission.sweep");
    }
}
