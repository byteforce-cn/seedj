package cn.byteforce.seedj.starter.audit;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.domain.event.CommandEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalCommandAuditAdapterTest {

    @Test
    void auditUsesObjectTypeNotCommandPrefix() {
        UUID id = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-06T09:00:00Z");
        CommandRecord record = CommandRecord.pending(id, "hr.employee.create", now, issuer,
                Map.of(), "k-1", "a".repeat(64), now).accepted(UUID.randomUUID(), 1L,
                List.of());
        CommandEvent event = new CommandEvent(UUID.randomUUID(), "hr.employee.created", now,
                now, issuer, List.of(id), Map.of(), "k-1:0", 1);
        AtomicReference<AuditAppendRequest<String>> captured = new AtomicReference<>();

        new TemporalCommandAuditAdapter(captured::set, () -> "tester")
                .accepted(record, "employee", List.of(event));

        assertThat(captured.get().getResourceType()).isEqualTo("employee");
        assertThat(captured.get().getAction()).isEqualTo("hr.employee.create");
        assertThat(captured.get().getEvents()).hasSize(1);
    }
}
