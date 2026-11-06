package cn.byteforce.seedj.starter.audit;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.audit.Audited;
import cn.byteforce.seedj.cqrs.Command;
import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnotationCommandAuditTest {

    private final List<AuditAppendRequest<String>> appended = new ArrayList<>();

    private final List<SecurityAuditEvent> rejected = new ArrayList<>();

    private final AuditAppender<String> appender = appended::add;

    private final SecurityAuditSink sink = rejected::add;

    private final AnnotationCommandAudit audit = new AnnotationCommandAudit(
            appender, provider(sink), () -> "audit-1");

    @Test
    @DisplayName("已标注命令写入动作、资源类型与聚合 ID；resourceType 缺省由类名推导")
    void annotatedCommandIsAudited() {
        audit.accepted(new AnnotatedCommand("e-1"), "e-1");

        assertThat(appended).hasSize(1);
        AuditAppendRequest<String> request = appended.get(0);
        assertThat(request.getAction()).isEqualTo("employee.onboarded");
        assertThat(request.getActor()).isEqualTo("system");
        assertThat(request.getResourceType()).isEqualTo("annotated");
        assertThat(request.getResourceId()).isEqualTo("e-1");
        assertThat(request.getPayload().get("command").asString()).isEqualTo("AnnotatedCommand");
        assertThat(request.getPayload().get("aggregateId").asString()).isEqualTo("e-1");
    }

    @Test
    @DisplayName("未标注命令不写任何审计")
    void unannotatedCommandIsIgnored() {
        audit.accepted(new PlainCommand("e-2"), "e-2");

        assertThat(appended).isEmpty();
        assertThat(rejected).isEmpty();
    }

    @Test
    @DisplayName("业务拒绝走安全审计通道，不进哈希链")
    void rejectionGoesToSecurityAudit() {
        audit.rejected(new AnnotatedCommand("e-3"), "e-3",
                new IllegalStateException("already onboarded"));

        assertThat(appended).isEmpty();
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).action()).isEqualTo("employee.onboarded");
        assertThat(rejected.get(0).outcome()).isEqualTo("REJECTED");
        assertThat(rejected.get(0).resourceId()).isEqualTo("e-3");
    }

    @Test
    @DisplayName("没有 SecurityAuditSink 时拒绝记录不影响命令结果")
    void rejectionWithoutSinkIsHarmless() {
        AnnotationCommandAudit withoutSink = new AnnotationCommandAudit(
                appender, provider(null), () -> "audit-2");

        withoutSink.rejected(new AnnotatedCommand("e-4"), "e-4",
                new IllegalStateException("rejected"));

        assertThat(appended).isEmpty();
    }

    @Audited(action = "employee.onboarded", resourceType = "")
    record AnnotatedCommand(String id) implements Command<String> {
        @Override
        public String aggregateId() {
            return id;
        }
    }

    record PlainCommand(String id) implements Command<String> {
        @Override
        public String aggregateId() {
            return id;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
