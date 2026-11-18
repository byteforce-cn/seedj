package cn.byteforce.seedj.ai;

import cn.byteforce.seedj.core.utils.CanonicalJsonHelper;
import cn.byteforce.seedj.core.utils.HashesHelper;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 参数指纹：命令“具体做什么”的摘要，审批与影响面比对用。
 *
 * <p>哈希前像为 {@code {type, payload, targets, expectedVersion}} 的审计规范化文本（键字典序、无空白）；
 * {@code expectedVersion} 键始终存在，未声明版本时为 {@code null} 字面量——因此“显式 null”与“某个版本”
 * 一定是两个不同的指纹。前缀 {@code sha256:} 标明算法，摘要为 64 位小写十六进制。
 *
 * <p>与 {@link IdemFingerprint} 的口径差异是刻意的：版本变化意味着影响面变化，必须换指纹； 而同一幂等键的重放不因版本不同变成新请求。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ArgsHash {

    /** 摘要算法前缀，出现在返回值最前面。 */
    private static final String PREFIX = "sha256:";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ArgsHash() {}

    /**
     * 计算参数指纹。
     *
     * @param fingerprint 指纹输入
     * @return {@code "sha256:"} 前缀加 64 位小写十六进制摘要
     * @throws NullPointerException {@code fingerprint} 为 {@code null}
     */
    public static String of(CommandFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return PREFIX
                + HashesHelper.sha256Hex(
                        CanonicalJsonHelper.canonicalize(preimage(fingerprint)));
    }

    private static ObjectNode preimage(CommandFingerprint fingerprint) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", fingerprint.type());
        node.set("payload", MAPPER.valueToTree(fingerprint.payload()));
        ArrayNode targets = node.putArray("targets");
        for (UUID target : fingerprint.targets()) {
            targets.add(target.toString());
        }
        if (fingerprint.expectedVersion() == null) {
            node.putNull("expectedVersion");
        } else {
            node.put("expectedVersion", fingerprint.expectedVersion().longValue());
        }
        return node;
    }
}
