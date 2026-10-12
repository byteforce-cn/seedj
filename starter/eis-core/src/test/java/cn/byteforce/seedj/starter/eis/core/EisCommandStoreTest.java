package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.node.StringNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisCommandStore} JDBC 组装测试（幂等占位 / 命令写入 / 终态 / 查询映射）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisCommandStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static final UUID COMMAND_ID = UUID.fromString("01900000-0000-7000-8000-0000000000c1");

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID EVENT_ID = UUID.fromString("01900000-0000-7000-8000-0000000000e1");

    private static final UUID SUBJECT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private JdbcTemplate jdbc;

    private EisCommandStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new EisCommandStore(jdbc);
    }

    @Test
    void findIdempotencyMapsRow() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("fingerprint-1");
            when(rs.getObject(2, UUID.class)).thenReturn(COMMAND_ID);
            try {
                return List.of(mapper.mapRow(rs, 0));
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });

        Optional<EisCommandStore.EisIdempotencyRecord> record = store.findIdempotency("k1");

        assertThat(record).isPresent();
        assertThat(record.get().getFingerprint()).isEqualTo("fingerprint-1");
        assertThat(record.get().getCommandId()).isEqualTo(COMMAND_ID);
    }

    @Test
    void insertIdempotencyIfAbsentReturnsInsertResult() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(store.insertIdempotencyIfAbsent("k1", "fp", COMMAND_ID)).isTrue();

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThat(store.insertIdempotencyIfAbsent("k1", "fp", COMMAND_ID)).isFalse();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("ON CONFLICT (idempotency_key) DO NOTHING");
        assertThat(argsCaptor.getValue()).containsExactly("k1", "fp", COMMAND_ID);
    }

    @Test
    void insertCommandBindsAllColumns() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.insertCommand(COMMAND_ID, "iam.user.create", NOW, ACTOR, List.of(COMMAND_ID),
                StringNode.valueOf("x"), "k9");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("INSERT INTO command").contains("CAST(? AS uuid[])").
                contains("CAST(? AS jsonb)");
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo(COMMAND_ID);
        assertThat(args[1]).isEqualTo("iam.user.create");
        assertThat(args[4]).isEqualTo("{" + COMMAND_ID + "}");
        assertThat(args[5]).isEqualTo("\"x\"");
        assertThat(args[6]).isEqualTo("k9");

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        store.insertCommand(COMMAND_ID, "iam.user.create", NOW, ACTOR, List.of(), null, "k10");
        ArgumentCaptor<Object[]> secondArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), secondArgs.capture());
        assertThat(secondArgs.getAllValues().get(1)[4]).isEqualTo("{}");
        assertThat(secondArgs.getAllValues().get(1)[5]).isEqualTo("{}");
    }

    @Test
    void markAcceptedWritesStatusAndOrderedLinks() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        List<UUID> events = List.of(EVENT_ID, UUID.randomUUID());

        store.markAccepted(COMMAND_ID, events);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(3)).update(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getAllValues().get(0)).contains("SET status = 'accepted'");
        assertThat(sqlCaptor.getAllValues().get(1)).contains("INSERT INTO eis_command_event");
        assertThat(sqlCaptor.getAllValues().get(2)).contains("INSERT INTO eis_command_event");
    }

    @Test
    void markAcceptedWithCreatedWritesRef() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.markAccepted(COMMAND_ID, List.of(EVENT_ID), new EisObjectRef("demo_thing", SUBJECT));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(3)).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(2)).contains("INSERT INTO eis_command_created");
        assertThat(argsCaptor.getAllValues().get(2)).containsExactly(COMMAND_ID, "demo_thing", SUBJECT);
    }

    @Test
    void markRejectedWritesReasonAndCode() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.markRejected(COMMAND_ID, "IAM-409-001", "用户名已存在");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(0)).contains("SET status = 'rejected', rejection_reason = ?");
        assertThat(argsCaptor.getAllValues().get(0)).containsExactly("用户名已存在", COMMAND_ID);
        assertThat(sqlCaptor.getAllValues().get(1)).contains("INSERT INTO eis_command_rejection");
    }

    @Test
    void findCommandAssemblesStatusEventsAndCode() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM command WHERE")) {
                RowMapper<?> mapper = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject(1, UUID.class)).thenReturn(COMMAND_ID);
                when(rs.getString(2)).thenReturn("iam.user.create");
                when(rs.getObject(3, OffsetDateTime.class)).thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
                when(rs.getObject(4, UUID.class)).thenReturn(ACTOR);
                when(rs.getString(5)).thenReturn("rejected");
                when(rs.getString(6)).thenReturn("用户名已存在");
                try {
                    return List.of(mapper.mapRow(rs, 0));
                } catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            if (sql.contains("FROM eis_command_event")) {
                return List.of(EVENT_ID);
            }
            if (sql.contains("FROM eis_command_rejection")) {
                return List.of("IAM-409-001");
            }
            if (sql.contains("FROM eis_command_created")) {
                return List.of(new EisObjectRef("demo_thing", SUBJECT));
            }
            throw new IllegalStateException("未预期 SQL: " + sql);
        });

        EisCommandStore.EisStoredCommand command = store.findCommand(COMMAND_ID).orElseThrow();

        assertThat(command.getType()).isEqualTo("iam.user.create");
        assertThat(command.getStatus()).isEqualTo("rejected");
        assertThat(command.getRejectionCode()).isEqualTo("IAM-409-001");
        assertThat(command.getRejectionReason()).isEqualTo("用户名已存在");
        assertThat(command.getEventIds()).containsExactly(EVENT_ID);
        assertThat(command.getIssuedAt()).isEqualTo(NOW);
        assertThat(command.getIssuer()).isEqualTo(ACTOR);
        assertThat(command.getCreated()).isNotNull();
        assertThat(command.getCreated().getObjectType()).isEqualTo("demo_thing");
        assertThat(command.getCreated().getId()).isEqualTo(SUBJECT);
    }

    @Test
    void findCommandMissingReturnsEmpty() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertThat(store.findCommand(COMMAND_ID)).isEmpty();
    }
}
