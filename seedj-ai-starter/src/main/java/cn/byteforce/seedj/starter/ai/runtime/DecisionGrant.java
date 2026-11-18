package cn.byteforce.seedj.starter.ai.runtime;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次决策的结论：放行时带 token（仅执行工具的步骤），拒绝时带原因码。
 *
 * <p><b>放行与拒绝是同一个返回值</b>：{@link #allowed()} 只看 {@code denyRule} 是否为空。决策面对 {@code deny} 必给原因码（{@code
 * CONTRACT_UNKNOWN} / {@code CONTRACT_CAPABILITY}），所以「有原因码即拒绝」 是无歧义的。
 *
 * <p>{@code token} 只在 {@code allow + execute_tool} 时出现；只读、追问、升级与 {@code finish} 的放行没有 token。{@code
 * expiresAt} 与 {@code token} 同时出现或同时为空。
 *
 * @param decisionId 决策标识，同时是命令的幂等键
 * @param token JWS compact 串；没有 token 时为空
 * @param denyRule 拒绝原因码；放行时为空
 * @param expiresAt token 过期时间；没有 token 时为空
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionGrant(UUID decisionId, String token, String denyRule, Instant expiresAt) {

    /**
     * 校验决策标识。
     *
     * @throws NullPointerException {@code decisionId} 为 {@code null}
     */
    public DecisionGrant {
        if (decisionId == null) {
            throw new NullPointerException("decisionId");
        }
    }

    /**
     * 是否放行。
     *
     * @return {@code denyRule} 为空时返回 {@code true}
     */
    public boolean allowed() {
        return denyRule == null;
    }
}
