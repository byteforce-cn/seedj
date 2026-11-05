package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsAuditAppenderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final AuditMetrics metrics = new MicrometerAuditMetrics(registry);

    private final AuditAppendRequest<String> request =
            new AuditAppendRequest<>("employee.onboarded", "alice", "employee", "e-1", null);

    @Test
    @DisplayName("成功 append 打 success 标签")
    void successIsTagged() {
        AuditAppender<String> appender = new MetricsAuditAppender(req -> {
        }, metrics);

        appender.append(request);

        assertThat(registry.get("seedj.audit.append.time")
                .tag("action", "employee.onboarded")
                .tag("resourceType", "employee")
                .tag("status", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败 append 打 failed 标签并原样上抛")
    void failureIsTagged() {
        AuditAppender<String> appender = new MetricsAuditAppender(req -> {
            throw new IllegalStateException("store down");
        }, metrics);

        assertThatThrownBy(() -> appender.append(request))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("seedj.audit.append.time")
                .tag("status", "failed")
                .timer().count()).isEqualTo(1);
    }
}
