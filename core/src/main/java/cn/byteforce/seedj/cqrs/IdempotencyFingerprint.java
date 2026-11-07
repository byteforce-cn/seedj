package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.utils.CanonicalJsonHelper;
import cn.byteforce.seedj.core.utils.HashesHelper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 幂等指纹：同幂等键重复提交时的内容比对依据。
 *
 * <p>算法与参考内核一致：对 {@code {type, payload, targets}} 做 {@code json.dumps(sort_keys=True,
 * ensure_ascii=False)}，再取 SHA-256 小写十六进制。 只存摘要，不存原始载荷；载荷键序不影响结果，{@code targets} 顺序参与结果。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IdempotencyFingerprint {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private IdempotencyFingerprint() {}

    /**
     * 计算一次提交的指纹。
     *
     * @param submission 命令提交
     * @return 64 字符小写十六进制摘要
     */
    public static String of(CommandSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        return of(submission.type(), submission.payload(), submission.targets());
    }

    /**
     * 计算显式参数组合的指纹。
     *
     * @param type 命令类型
     * @param payload 载荷
     * @param targets 目标对象 ID
     * @return 64 字符小写十六进制摘要
     */
    public static String of(String type, Map<String, Object> payload, List<UUID> targets) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        List<UUID> safeTargets = targets == null ? List.of() : targets;

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", type);
        canonical.put("payload", payload);
        canonical.put("targets", safeTargets.stream().map(UUID::toString).toList());

        JsonNode node = MAPPER.valueToTree(canonical);
        return HashesHelper.sha256Hex(CanonicalJsonHelper.pythonDumps(node));
    }
}
