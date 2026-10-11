package cn.byteforce.seedj.starter.eis.audit;

import cn.byteforce.seedj.starter.eis.core.EisAuditAppender;
import cn.byteforce.seedj.starter.eis.core.EisClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisAuditChain} 串行分配 / 写入 / 验证 / 锚定测试（Mockito 捕获 SQL 与绑定）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuditChainTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID SUBJECT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private JdbcTemplate jdbc;

    private EisAuditChain chain;

    private final List<EisAuditHead> anchored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        chain = new EisAuditChain(jdbc, EisClock.fixed(NOW), anchored::add);
    }

    @Test
    void appendAllocatesSequenceComputesHashAndUpdatesHead() {
        when(jdbc.queryForObject(contains("RETURNING last_sequence"), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(1)).thenReturn(7L);
                    when(rs.getString(2)).thenReturn("a".repeat(64));
                    return mapper.mapRow(rs, 0);
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        chain.append(new EisAuditAppender.EisAuditAppendRequest("iam.user.create", ACTOR, "iam", SUBJECT,
                MAPPER.readTree("{\"command_id\":\"x\"}")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(sqlCaptor.capture(), argsCaptor.capture());
        List<String> sqls = sqlCaptor.getAllValues();
        List<Object[]> args = argsCaptor.getAllValues();

        assertThat(sqls.get(0)).contains("INSERT INTO audit_entry").contains("CAST(? AS jsonb)");
        assertThat(args.get(0)[1]).isEqualTo(7L);
        assertThat(args.get(0)[5]).isEqualTo("iam");
        assertThat(args.get(0)[6]).isEqualTo(SUBJECT);
        assertThat(args.get(0)[9]).isEqualTo("a".repeat(64));
        String insertedHash = (String) args.get(0)[8];
        assertThat(insertedHash).matches(HEX64);

        assertThat(sqls.get(1)).contains("SET last_hash = ?");
        assertThat(args.get(1)[0]).isEqualTo(insertedHash);
    }

    @Test
    void verifyLoadsAndValidatesWholeChain() {
        EisAuditEntry valid = new EisAuditEntry(UUID.randomUUID(), 1L, NOW, ACTOR, "iam.user.create", "iam",
                SUBJECT, MAPPER.readTree("{\"command_id\":\"x\"}"), null, CanonicalHasher.GENESIS_PREV_HASH,
                null);
        String hash = CanonicalHasher.computeHash(valid);
        EisAuditEntry stored = new EisAuditEntry(valid.getId(), 1L, NOW, ACTOR, "iam.user.create", "iam",
                SUBJECT, valid.getPayload(), hash, CanonicalHasher.GENESIS_PREV_HASH, null);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1, UUID.class)).thenReturn(stored.getId());
            when(rs.getLong(2)).thenReturn(1L);
            when(rs.getObject(3, java.time.OffsetDateTime.class))
                    .thenReturn(java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
            when(rs.getObject(4, UUID.class)).thenReturn(ACTOR);
            when(rs.getString(5)).thenReturn("iam.user.create");
            when(rs.getString(6)).thenReturn("iam");
            when(rs.getObject(7, UUID.class)).thenReturn(SUBJECT);
            when(rs.getString(8)).thenReturn("{\"command_id\":\"x\"}");
            when(rs.getString(9)).thenReturn(hash);
            when(rs.getString(10)).thenReturn(CanonicalHasher.GENESIS_PREV_HASH);
            when(rs.getString(11)).thenReturn(null);
            return List.of(mapper.mapRow(rs, 0));
        });

        assertThat(chain.verify()).isEmpty();
        assertThat(chain.loadEntries()).hasSize(1);
    }

    @Test
    void headReadsAnchorRow() {
        when(jdbc.queryForObject(contains("SELECT last_sequence"), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(1)).thenReturn(9L);
                    when(rs.getString(2)).thenReturn("b".repeat(64));
                    return mapper.mapRow(rs, 0);
                });

        EisAuditHead head = chain.head();

        assertThat(head.getSequence()).isEqualTo(9L);
        assertThat(head.getHash()).isEqualTo("b".repeat(64));
        assertThat(head.getAnchoredAt()).isEqualTo(NOW);
    }

    @Test
    void anchorNowDelegatesToAnchorSpi() {
        when(jdbc.queryForObject(contains("SELECT last_sequence"), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(1)).thenReturn(3L);
                    when(rs.getString(2)).thenReturn("c".repeat(64));
                    return mapper.mapRow(rs, 0);
                });

        EisAuditHead head = chain.anchorNow();

        assertThat(head.getSequence()).isEqualTo(3L);
        assertThat(anchored).hasSize(1);
        assertThat(anchored.get(0).getHash()).isEqualTo("c".repeat(64));
    }
}
