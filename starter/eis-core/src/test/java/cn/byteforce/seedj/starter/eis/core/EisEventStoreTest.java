package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisEventStore} JDBC 组装测试（事件写入 / outbox 生命周期 / 映射）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisEventStoreTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static final UUID EVENT_ID = UUID.fromString("01900000-0000-7000-8000-0000000000e1");

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private JdbcTemplate jdbc;

    private EisEventStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new EisEventStore(jdbc);
    }

    @Test
    void insertBindsEventColumns() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        EisEvent event = new EisEvent(EVENT_ID, "iam.user.created", NOW, NOW, ACTOR,
                List.of(ACTOR, EVENT_ID), MAPPER.readTree("{\"a\":1}"), EVENT_ID + ":rule:0", 1);

        store.insert(event);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("INSERT INTO event").contains("CAST(? AS uuid[])")
                .contains("CAST(? AS jsonb)");
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo(EVENT_ID);
        assertThat(args[1]).isEqualTo("iam.user.created");
        assertThat(args[5]).isEqualTo("{" + ACTOR + "," + EVENT_ID + "}");
        assertThat(args[6]).isEqualTo("{\"a\":1}");
        assertThat(args[8]).isEqualTo(1);
    }

    @Test
    void insertBindsEmptyPayloadAndSubjectsDefaults() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        EisEvent event = new EisEvent(EVENT_ID, "iam.user.created", NOW, NOW, ACTOR, null, null, "k", 1);

        store.insert(event);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[5]).isEqualTo("{}");
        assertThat(argsCaptor.getValue()[6]).isEqualTo("{}");
    }

    @Test
    void outboxLifecycle() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        EisOutboxDispatcher.EisOutboxEntry entry = new EisOutboxDispatcher.EisOutboxEntry(
                UUID.randomUUID(), EVENT_ID, "iam.events", MAPPER.readTree("{\"a\":1}"), NOW);

        store.insertOutbox(entry);
        store.markDispatched(entry.getId(), NOW.plusSeconds(1));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(0)).contains("INSERT INTO eis_outbox");
        assertThat(sqlCaptor.getAllValues().get(1)).contains("SET dispatched_at = ?");
    }

    @Test
    void findUndispatchedMapsRows() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1, UUID.class)).thenReturn(EVENT_ID);
            when(rs.getObject(2, UUID.class)).thenReturn(EVENT_ID);
            when(rs.getString(3)).thenReturn("iam.events");
            when(rs.getString(4)).thenReturn("{\"a\":1}");
            when(rs.getObject(5, OffsetDateTime.class)).thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
            try {
                return List.of(mapper.mapRow(rs, 0));
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });

        List<EisOutboxDispatcher.EisOutboxEntry> entries = store.findUndispatched(10);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getTopic()).isEqualTo("iam.events");
        assertThat(entries.get(0).getPayload().get("a").asInt()).isEqualTo(1);
        assertThat(entries.get(0).getCreatedAt()).isEqualTo(NOW);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sqlCaptor.getValue()).contains("dispatched_at IS NULL").contains("ORDER BY created_at ASC");
    }
}
