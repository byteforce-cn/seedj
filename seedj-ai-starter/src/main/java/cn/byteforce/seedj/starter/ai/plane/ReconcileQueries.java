package cn.byteforce.seedj.starter.ai.plane;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 对账读取业务库的唯一入口：{@code db/ai/reconcile.sql} 里的两条命名查询。
 *
 * <p>查询文本在资源文件里，不在 Java 里：它们是运维与排障时唯一要照着看的东西，改关联方式（U9 直链
 * {@code decision_id}）也只该改那一处。命名语句由 {@code -- name:} 切分，语句内用 {@code ?} 作位置参数。
 *
 * <p><b>只读</b>：本类没有任何写入方法——业务库的写入属于命令事务，对账只读事实、回写账本。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ReconcileQueries {

    /** 按 {@code (issuer, idempotency_key)} 取命令记录。 */
    static final String COMMAND_BY_IDEMPOTENCY = "command_by_idempotency";

    /** 按 {@code payload->>'command_id'} 取链条目（U9 前唯一的关联方式）。 */
    static final String AUDIT_BY_COMMAND = "audit_by_command";

    private static final String RESOURCE = "db/ai/reconcile.sql";

    private static final String NAME_MARKER = "-- name:";

    private static final Map<String, String> STATEMENTS = load();

    private static final RowMapper<CommandRow> COMMAND_ROW =
            (resultSet, rowNum) ->
                    new CommandRow(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("status"),
                            resultSet.getString("rejection_code"),
                            resultSet.getString("payload_fingerprint"));

    private static final RowMapper<String> AUDIT_ENTRY_ID =
            (resultSet, rowNum) -> resultSet.getString("id");

    private final JdbcTemplate jdbc;

    /**
     * 绑定业务库数据源。
     *
     * @param dataSource 只读使用的数据源
     * @throws NullPointerException {@code dataSource} 为 {@code null}
     */
    ReconcileQueries(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 按 guard 保证的「幂等键 = decision_id」查命令记录。
     *
     * @param issuer 发起主体（intent 的 {@code identity.actor}）
     * @param idempotencyKey 幂等键（决策标识的字符串形式）
     * @return 命令记录；没有时为空
     */
    Optional<CommandRow> commandByIdempotency(UUID issuer, String idempotencyKey) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return jdbc.query(
                        statement(COMMAND_BY_IDEMPOTENCY), COMMAND_ROW, issuer, idempotencyKey)
                .stream()
                .findFirst();
    }

    /**
     * 查命令对应的链条目标识。
     *
     * @param commandId 命令记录标识
     * @return 链条目标识；没有对应条目时为空
     */
    Optional<String> auditEntryIdOf(UUID commandId) {
        Objects.requireNonNull(commandId, "commandId");
        return jdbc.query(statement(AUDIT_BY_COMMAND), AUDIT_ENTRY_ID, commandId.toString())
                .stream()
                .findFirst();
    }

    private static String statement(String name) {
        String sql = STATEMENTS.get(name);
        if (sql == null) {
            throw new IllegalStateException(RESOURCE + " 缺少命名查询: " + name);
        }
        return sql;
    }

    /** 按 {@code -- name:} 切分命名语句；首个标记之前的文件头注释不构成语句。 */
    private static Map<String, String> load() {
        Map<String, String> statements = new LinkedHashMap<>();
        StringBuilder body = null;
        String name = null;
        for (String line : read().split("\n", -1)) {
            if (line.strip().startsWith(NAME_MARKER)) {
                if (name != null) {
                    statements.put(name, body.toString().strip());
                }
                name = line.strip().substring(NAME_MARKER.length()).strip();
                body = new StringBuilder();
            } else if (body != null) {
                body.append(line).append('\n');
            }
        }
        if (name != null) {
            statements.put(name, body.toString().strip());
        }
        return Map.copyOf(statements);
    }

    private static String read() {
        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("缺少对账查询资源 " + RESOURCE, error);
        }
    }

    /**
     * {@code command_record} 的一行：判定要用的四列。
     *
     * @param id 命令记录标识（上报阶段写进 outcome 的 {@code command_id} 只来自这里）
     * @param status 命令状态：{@code pending} / {@code accepted} / {@code rejected}
     * @param rejectionCode 业务拒绝码；仅 {@code rejected} 时有值
     * @param payloadFingerprint 命令的幂等指纹，与本条 intent 的幂等指纹比对
     */
    record CommandRow(UUID id, String status, String rejectionCode, String payloadFingerprint) {}
}
