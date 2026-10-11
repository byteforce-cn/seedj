package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisObjectRepository} JDBC 组装与双时间轴语义测试（Mockito 捕获 SQL / 绑定 / 行映射）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisObjectRepositoryTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static final UUID ID = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID REF = UUID.fromString("01900000-0000-7000-8000-0000000000bb");

    private JdbcTemplate jdbc;

    private EisObjectRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        EisObjectType type = EisObjectType.builder("t_item")
                .field("name", EisFieldKind.TEXT)
                .field("active", EisFieldKind.BOOLEAN)
                .field("ref", EisFieldKind.UUID)
                .field("at", EisFieldKind.TIMESTAMP)
                .field("meta", EisFieldKind.JSONB)
                .field("tags", EisFieldKind.TEXT_ARRAY)
                .build();
        repository = new EisObjectRepository(jdbc, new EisObjectTypeRegistry(List.of(type)));
    }

    private static Map<String, Object> fullData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "alice");
        data.put("active", true);
        data.put("ref", REF);
        data.put("at", NOW.minusSeconds(30));
        data.put("meta", MAPPER.readTree("{\"k\":1}"));
        data.put("tags", List.of("a"));
        return data;
    }

    @Test
    void createBindsAllColumnsAndReturnsRow() throws Exception {
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

        EisObjectRow row = repository.create("t_item", ID, fullData(), ACTOR, NOW);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).update(sqlCaptor.capture(), setterCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("INSERT INTO \"t_item\"").contains("CAST(? AS jsonb)");

        PreparedStatement ps = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        Array pgArray = mock(Array.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), any())).thenReturn(pgArray);
        setterCaptor.getValue().setValues(ps);

        verify(ps).setObject(1, ID);
        verify(ps).setLong(2, 1L);
        verify(ps).setObject(7, ACTOR);
        verify(ps).setString(11, "alice");
        verify(ps).setBoolean(12, true);
        verify(ps).setObject(13, REF);
        verify(ps).setString(15, "{\"k\":1}");
        verify(connection).createArrayOf("text", new Object[]{"a"});
        verify(ps).setArray(16, pgArray);

        assertThat(row.getVersion()).isEqualTo(1L);
        assertThat(row.getValidFrom()).isEqualTo(NOW);
        assertThat(row.getRecordedAt()).isEqualTo(NOW);
        assertThat(row.getSupersededAt()).isNull();
        assertThat(row.getData()).containsEntry("name", "alice");
    }

    @Test
    void createBindsNullsForAllKinds() throws Exception {
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
        Map<String, Object> nulls = new LinkedHashMap<>();
        for (String field : List.of("name", "active", "ref", "at", "meta", "tags")) {
            nulls.put(field, null);
        }

        repository.create("t_item", ID, nulls, ACTOR, NOW);

        ArgumentCaptor<PreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).update(anyString(), setterCaptor.capture());
        PreparedStatement ps = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(ps);

        verify(ps).setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
        verify(ps).setNull(9, Types.OTHER);
        verify(ps).setNull(11, Types.VARCHAR);
        verify(ps).setNull(12, Types.BOOLEAN);
        verify(ps).setNull(13, Types.OTHER);
        verify(ps).setNull(14, Types.TIMESTAMP_WITH_TIMEZONE);
        verify(ps).setNull(15, Types.OTHER);
        verify(ps).setNull(16, Types.ARRAY);
    }

    @Test
    void createRejectsUnregisteredFieldAndType() {
        assertThatThrownBy(() -> repository.create("t_item", ID, Map.of("nope", "x"), ACTOR, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册字段");
        assertThatThrownBy(() -> repository.create("missing", ID, Map.of(), ACTOR, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册对象类型");
    }

    @Test
    void updateSealsCurrentAndAppendsMergedVersion() throws Exception {
        EisObjectRow current = new EisObjectRow("t_item", ID, 1L, NOW.minusSeconds(60), null,
                NOW.minusSeconds(30), null, ACTOR, NOW.minusSeconds(60), null, null, fullData());
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(current));
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

        EisObjectRow updated = repository.update("t_item", ID, Map.of("name", "bob"), ACTOR, NOW);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), setterCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(0)).startsWith("UPDATE \"t_item\"").contains("\"superseded_at\" IS NULL");
        assertThat(sqlCaptor.getAllValues().get(1)).startsWith("INSERT INTO \"t_item\"");

        PreparedStatement ps = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), any())).thenReturn(mock(Array.class));
        setterCaptor.getAllValues().get(0).setValues(ps);
        setterCaptor.getAllValues().get(1).setValues(ps);

        assertThat(updated.getVersion()).isEqualTo(2L);
        assertThat(updated.getValidFrom()).isEqualTo(NOW);
        assertThat(updated.getRecordedAt()).isEqualTo(NOW);
        assertThat(updated.getData()).containsEntry("name", "bob").containsEntry("active", true);
    }

    @Test
    void updateAdvancesTimeWhenClockIsNotAfterCurrentRecordedAt() {
        Instant recordedAt = NOW.plusSeconds(5);
        EisObjectRow current = new EisObjectRow("t_item", ID, 3L, recordedAt, null, recordedAt, null,
                ACTOR, recordedAt, null, null, fullData());
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(current));
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

        EisObjectRow updated = repository.update("t_item", ID, Map.of("name", "bob"), ACTOR, NOW);

        assertThat(updated.getValidFrom()).isEqualTo(recordedAt.plusNanos(1_000L));
    }

    @Test
    void updateMissingCurrentIsRejectedWithEis404() {
        assertThatThrownBy(() -> repository.update("t_item", ID, Map.of("name", "bob"), ACTOR, NOW))
                .isInstanceOf(EisCommandRejectedException.class)
                .satisfies(ex -> assertThat(((EisCommandRejectedException) ex).getCode()).isEqualTo("EIS-404-001"));
    }

    @Test
    void findCurrentMapsFullRow() throws Exception {
        stubSingleRowAnswer(fullRowResultSet());

        Optional<EisObjectRow> found = repository.findCurrent("t_item", ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sqlCaptor.getValue()).contains("\"superseded_at\" IS NULL");
        assertThat(found).isPresent();
        EisObjectRow row = found.get();
        assertThat(row.getId()).isEqualTo(ID);
        assertThat(row.getVersion()).isEqualTo(2L);
        assertThat(row.getValidFrom()).isEqualTo(NOW.minusSeconds(60));
        assertThat(row.getValidTo()).isNull();
        assertThat(row.getData()).containsEntry("name", "alice");
        assertThat(row.getData()).containsEntry("active", true);
        assertThat(row.getData().get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(row.getData().get("meta")).isInstanceOf(JsonNode.class);
    }

    @Test
    void findCurrentMapsNullCollectionsAsEmpty() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1, UUID.class)).thenReturn(ID);
        when(rs.getLong(2)).thenReturn(1L);
        when(rs.getObject(3, OffsetDateTime.class)).thenReturn(odt(NOW));
        when(rs.getObject(5, OffsetDateTime.class)).thenReturn(odt(NOW));
        when(rs.getObject(7, UUID.class)).thenReturn(ACTOR);
        when(rs.getObject(8, OffsetDateTime.class)).thenReturn(odt(NOW));
        when(rs.getArray(16)).thenReturn(null);
        stubSingleRowAnswer(rs);

        EisObjectRow row = repository.findCurrent("t_item", ID).orElseThrow();

        assertThat(row.getData().get("tags")).isEqualTo(List.of());
        assertThat(row.getData().get("name")).isNull();
    }

    @Test
    void findCurrentAllByBuildsTypedConditions() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("meta", MAPPER.readTree("{\"k\":1}"));
        criteria.put("at", NOW);
        criteria.put("name", "alice");

        assertThat(repository.findCurrentAllBy("t_item", criteria)).isEmpty();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("\"superseded_at\" IS NULL")
                .doesNotContain("\"id\" = ?")
                .contains("\"meta\" = CAST(? AS jsonb)")
                .contains("\"at\" = ?")
                .contains("\"name\" = ?");
        assertThat(argsCaptor.getValue()).hasSize(3);
        assertThat(argsCaptor.getValue()[0]).isEqualTo("{\"k\":1}");
        assertThat(argsCaptor.getValue()[1]).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(argsCaptor.getValue()[2]).isEqualTo("alice");
    }

    @Test
    void findCurrentAllByRejectsUnregisteredConditionField() {
        assertThatThrownBy(() -> repository.findCurrentAllBy("t_item", Map.of("nope", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册字段");
    }

    @Test
    void asOfBuildsHalfOpenConditions() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        Instant point = NOW.minusSeconds(5);

        repository.asOf("t_item", ID, point, null);
        repository.asOf("t_item", ID, null, point);
        repository.asOf("t_item", ID, point, point);
        repository.asOf("t_item", ID, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(4)).query(sqlCaptor.capture(), any(RowMapper.class),
                argsCaptor.capture());
        List<String> sqls = sqlCaptor.getAllValues();
        assertThat(sqls.get(0))
                .contains("\"valid_from\" <= ? AND (\"valid_to\" IS NULL OR \"valid_to\" > ?)")
                .doesNotContain("\"recorded_at\" <= ?")
                .contains("ORDER BY \"version\" DESC LIMIT 1");
        assertThat(sqls.get(1))
                .doesNotContain("\"valid_from\" <= ?")
                .contains("\"recorded_at\" <= ? AND (\"superseded_at\" IS NULL OR \"superseded_at\" > ?)");
        assertThat(sqls.get(2)).contains("\"valid_from\" <= ?").contains("\"recorded_at\" <= ?");
        assertThat(sqls.get(3)).contains("\"superseded_at\" IS NULL");

        List<Object[]> args = argsCaptor.getAllValues();
        OffsetDateTime expected = OffsetDateTime.ofInstant(point, ZoneOffset.UTC);
        assertThat(args.get(0)).containsExactly(ID, expected, expected);
        assertThat(args.get(1)).containsExactly(ID, expected, expected);
        assertThat(args.get(2)).containsExactly(ID, expected, expected, expected, expected);
        assertThat(args.get(3)).containsExactly(ID);
    }

    @Test
    void historyOrdersByVersion() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertThat(repository.history("t_item", ID)).isEmpty();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sqlCaptor.getValue()).contains("ORDER BY \"version\" ASC");
    }

    private void stubSingleRowAnswer(ResultSet rs) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            try {
                return List.of(mapper.mapRow(rs, 0));
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static ResultSet fullRowResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1, UUID.class)).thenReturn(ID);
        when(rs.getLong(2)).thenReturn(2L);
        when(rs.getObject(3, OffsetDateTime.class)).thenReturn(odt(NOW.minusSeconds(60)));
        when(rs.getObject(4, OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject(5, OffsetDateTime.class)).thenReturn(odt(NOW.minusSeconds(30)));
        when(rs.getObject(6, OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject(7, UUID.class)).thenReturn(ACTOR);
        when(rs.getObject(8, OffsetDateTime.class)).thenReturn(odt(NOW.minusSeconds(60)));
        when(rs.getObject(9, UUID.class)).thenReturn(null);
        when(rs.getObject(10, OffsetDateTime.class)).thenReturn(null);
        when(rs.getString(11)).thenReturn("alice");
        when(rs.getObject(12, Boolean.class)).thenReturn(true);
        when(rs.getObject(13, UUID.class)).thenReturn(REF);
        when(rs.getObject(14, OffsetDateTime.class)).thenReturn(odt(NOW.minusSeconds(30)));
        when(rs.getString(15)).thenReturn("{\"k\":1}");
        Array tags = mock(Array.class);
        when(tags.getArray()).thenReturn(new String[]{"a", "b"});
        when(rs.getArray(16)).thenReturn(tags);
        return rs;
    }

    private static OffsetDateTime odt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
