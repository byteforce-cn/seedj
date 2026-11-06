package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.audit.AuditEvent;
import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.domain.event.CommandEvent;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 把临时命令的终态与事件翻译成审计追加请求。
 *
 * <p>每条受理命令都写链（不看 {@code @Audited}）；payload 固定为
 * {@code {command_id, event_ids}}，事件投影用于 v1.1 摘要复算。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TemporalCommandAuditAdapter implements TemporalCommandAudit {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditAppender<String> appender;

    private final Supplier<String> actor;

    /**
     * 构造适配器。
     *
     * @param appender 审计追加端口
     * @param actor    当前操作人解析；不得返回 {@code null}
     */
    public TemporalCommandAuditAdapter(AuditAppender<String> appender, Supplier<String> actor) {
        this.appender = Objects.requireNonNull(appender, "appender");
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    @Override
    public void accepted(CommandRecord record, List<CommandEvent> events) {
        List<CommandEvent> safeEvents = events == null ? List.of() : events;
        ObjectNode payload = JSON.createObjectNode();
        payload.put("command_id", record.id().toString());
        ArrayNode eventIds = payload.putArray("event_ids");
        List<AuditEvent> projections = new ArrayList<>(safeEvents.size());
        for (CommandEvent event : safeEvents) {
            eventIds.add(event.id().toString());
            projections.add(new AuditEvent(event.id(), event.type(),
                    JSON.valueToTree(event.payload())));
        }
        String resourceId = record.locator() == null ? null : record.locator().toString();
        appender.append(new AuditAppendRequest<>(record.type(), actor.get(),
                resourceType(record.type()), resourceId, payload, projections));
    }

    /**
     * 资源类型取命令类型的第一段：{@code employee.onboarded} → {@code employee}。
     */
    private static String resourceType(String commandType) {
        int dot = commandType.indexOf('.');
        return dot < 0 ? commandType : commandType.substring(0, dot);
    }
}
