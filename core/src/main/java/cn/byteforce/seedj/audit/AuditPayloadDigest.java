package cn.byteforce.seedj.audit;

import cn.byteforce.seedj.core.utils.CanonicalJsonHelper;
import cn.byteforce.seedj.core.utils.HashesHelper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 审计 {@code payload_digest} 的计算：事件投影的规范 JSON 再做 SHA-256。
 *
 * <p>投影固定为 {@code [{"id":…,"type":…,"payload":…}…]}，顺序由调用方按审计条目
 * {@code payload.event_ids} 给出。空投影使用规范冻结常量。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AuditPayloadDigest {

    /** 空投影 {@code []} 的冻结摘要。 */
    public static final String EMPTY_DIGEST =
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AuditPayloadDigest() {
    }

    /**
     * 计算事件投影摘要。
     *
     * @param events 按 {@code event_ids} 顺序排列的事件；{@code null} 视为空
     * @return 64 位小写 hex
     */
    public static String compute(List<AuditEvent> events) {
        if (events == null || events.isEmpty()) {
            return EMPTY_DIGEST;
        }
        ArrayNode projection = MAPPER.createArrayNode();
        for (AuditEvent event : events) {
            ObjectNode node = projection.addObject();
            node.put("id", event.id().toString());
            node.put("type", event.type());
            node.set("payload", event.payload());
        }
        return HashesHelper.sha256Hex(CanonicalJsonHelper.canonicalize(projection));
    }
}
