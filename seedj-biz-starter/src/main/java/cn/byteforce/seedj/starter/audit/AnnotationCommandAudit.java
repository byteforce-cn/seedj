package cn.byteforce.seedj.starter.audit;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.audit.Audited;
import cn.byteforce.seedj.cqrs.Command;
import cn.byteforce.seedj.cqrs.CommandAudit;
import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * 读 {@code @Audited} 并把命令翻译成审计写入。
 *
 * <p>受理记录进哈希链（与命令同事务）；拒绝记录走 {@link SecurityAuditSink}
 * （事务外、尽力而为，不进链）。</p>
 */
public final class AnnotationCommandAudit implements CommandAudit {

    private static final Logger log = LoggerFactory.getLogger(AnnotationCommandAudit.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditAppender<String> appender;

    private final ObjectProvider<SecurityAuditSink> securityAudit;

    private final IdGenerator<String> ids;

    public AnnotationCommandAudit(AuditAppender<String> appender,
                                  ObjectProvider<SecurityAuditSink> securityAudit,
                                  IdGenerator<String> ids) {
        this.appender = appender;
        this.securityAudit = securityAudit;
        this.ids = ids;
    }

    @Override
    public void accepted(Command<?> command, String resourceId) {
        Optional<Audited> audited = AuditedCommands.find(command.getClass());
        if (audited.isEmpty()) {
            return;
        }
        Audited annotation = audited.get();
        appender.append(new AuditAppendRequest<>(
                annotation.action(),
                AuditActor.current(),
                AuditedCommands.resourceType(annotation, command.getClass()),
                resourceId,
                payload(command, resourceId)));
    }

    @Override
    public void rejected(Command<?> command, String resourceId, RuntimeException error) {
        Optional<Audited> audited = AuditedCommands.find(command.getClass());
        if (audited.isEmpty()) {
            return;
        }
        Audited annotation = audited.get();
        try {
            SecurityAuditSink sink = securityAudit.getIfAvailable();
            if (sink == null) {
                log.warn("seedj audit rejected command {} but no SecurityAuditSink is available",
                        command.getClass().getSimpleName());
                return;
            }
            sink.record(SecurityAuditEvent.of(ids.id(), AuditActor.current(), annotation.action(),
                    AuditedCommands.resourceType(annotation, command.getClass()), resourceId,
                    "REJECTED", error.getMessage()));
        } catch (RuntimeException e) {
            log.warn("seedj audit rejected command {} could not be recorded",
                    command.getClass().getSimpleName(), e);
        }
    }

    /**
     * 只放命令名与聚合 ID：不序列化命令字段（PII、命令演化会让哈希漂移）。
     */
    private static JsonNode payload(Command<?> command, String resourceId) {
        return JSON.createObjectNode()
                .put("command", command.getClass().getSimpleName())
                .put("aggregateId", resourceId);
    }
}
