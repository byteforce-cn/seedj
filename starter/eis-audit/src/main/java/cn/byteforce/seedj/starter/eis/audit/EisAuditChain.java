package cn.byteforce.seedj.starter.eis.audit;

import cn.byteforce.seedj.starter.eis.core.CanonicalJson;
import cn.byteforce.seedj.starter.eis.core.EisAuditAppender;
import cn.byteforce.seedj.starter.eis.core.EisClock;
import cn.byteforce.seedj.starter.eis.core.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * EIS 审计哈希链（{@link EisAuditAppender} 的权威实现）。
 *
 * <p><b>写入语义</b>：</p>
 * <ul>
 *   <li><b>串行写</b>：序列由锚表 {@code eis_audit_head} 单行
 *       {@code UPDATE … RETURNING} 分配（{@code sequence = max + 1}），行锁在命令事务
 *       提交前保持——并发命令在审计处串行、提交后序号连续无缺口；</li>
 *   <li><b>不复用 BIGSERIAL 缺省分配</b>：序列列显式写值（评估口径：并发/回滚会留
 *       缺口，违反"连续无缺口"）；</li>
 *   <li><b>同事务</b>：与命令/事件同一事务写入，命令回滚则审计一并回滚（审计只记录
 *       已受理的事实）；</li>
 *   <li>链头即锚行：{@code last_hash} 恒等于最新条目哈希；空链时 = 创世前序哈希。</li>
 * </ul>
 *
 * <p><b>验证</b>：{@link #verify()} 全链重算（语义与 {@code verify.tool} 一致）；
 * <b>锚定</b>：{@link #anchorNow()} 经 {@link EisAuditAnchor} 导出链头。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuditChain implements EisAuditAppender {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    private final EisClock clock;

    private final EisAuditAnchor anchor;

    /**
     * 构造审计链。
     *
     * @param jdbc  JDBC 模板（参与命令事务）
     * @param clock EIS 时钟
     * @param anchor 锚定实现
     */
    public EisAuditChain(JdbcTemplate jdbc, EisClock clock, EisAuditAnchor anchor) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.anchor = anchor;
    }

    @Override
    public void append(EisAuditAppendRequest request) {
        HeadRow head = jdbc.queryForObject(
                "UPDATE eis_audit_head SET last_sequence = last_sequence + 1 WHERE id = 1"
                        + " RETURNING last_sequence, last_hash",
                (rs, rowNum) -> new HeadRow(rs.getLong(1), rs.getString(2)));
        long sequence = head.sequence;
        String prevHash = head.hash;
        JsonNode payload = request.getPayload() == null ? MAPPER.createObjectNode() : request.getPayload();
        // 全链路 UTC 微秒（T-R007）：纳秒截断为微秒后再入哈希/落库，保证回读重算一致
        EisAuditEntry entry = new EisAuditEntry(UuidV7.generate(clock),
                sequence, clock.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), request.getActor(),
                request.getAction(), request.getResourceType(), request.getResourceId(), payload, null, prevHash,
                null);
        String hash = CanonicalHasher.computeHash(entry);
        jdbc.update("INSERT INTO audit_entry (id, sequence, timestamp, actor, action, resource_type, resource_id,"
                        + " payload, hash, prev_hash, signature)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)",
                entry.getId(), entry.getSequence(),
                OffsetDateTime.ofInstant(entry.getTimestamp(), java.time.ZoneOffset.UTC),
                entry.getActor(), entry.getAction(), entry.getResourceType(), entry.getResourceId(),
                payload.toString(), hash, prevHash, entry.getSignature());
        jdbc.update("UPDATE eis_audit_head SET last_hash = ? WHERE id = 1", hash);
    }

    /**
     * 全链验证（语义与 {@code verify.tool} 一致）。
     *
     * @return 错误列表（空 = 通过）
     */
    public List<EisAuditVerifier.EisAuditViolation> verify() {
        return EisAuditVerifier.verify(loadEntries());
    }

    /**
     * 读取全链条目（按 sequence 升序）。
     *
     * @return 审计条目列表
     */
    public List<EisAuditEntry> loadEntries() {
        return jdbc.query("SELECT id, sequence, timestamp, actor, action, resource_type, resource_id,"
                        + " payload::text, hash, prev_hash, signature FROM audit_entry ORDER BY sequence ASC",
                (rs, rowNum) -> new EisAuditEntry(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                        CanonicalJson.parse(rs.getString(8)), rs.getString(9), rs.getString(10),
                        rs.getString(11)));
    }

    /**
     * 读取链头快照（锚行）。
     *
     * @return 链头（含当前时刻）
     */
    public EisAuditHead head() {
        HeadRow head = jdbc.queryForObject("SELECT last_sequence, last_hash FROM eis_audit_head WHERE id = 1",
                (rs, rowNum) -> new HeadRow(rs.getLong(1), rs.getString(2)));
        return new EisAuditHead(head.sequence, head.hash, clock.now());
    }

    /**
     * 立即锚定链头（运维/定时任务动作）。
     *
     * @return 已锚定的链头快照
     */
    public EisAuditHead anchorNow() {
        EisAuditHead head = head();
        anchor.anchor(head);
        return head;
    }

    /**
     * 锚表行视图。
     */
    private static final class HeadRow {

        private final long sequence;

        private final String hash;

        private HeadRow(long sequence, String hash) {
            this.sequence = sequence;
            this.hash = hash;
        }
    }
}
