package cn.byteforce.seedj.ai;

import java.util.Objects;

/**
 * 守卫结论：放行（带上已校验的 claim）或拒绝（带上稳定错误码）。
 *
 * <p>放行必须携带 claim：调用方拿到 {@link Admit} 就能立即转发命令，不需要再解一次 token； 拒绝必须携带 {@link
 * AiErrorCode}：HTTP 状态与告警规则按 code 映射，文本里的自由描述只用于排障。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public sealed interface GuardVerdict {

    /**
     * 放行：claim 已通过全部校验。
     *
     * @param claims 已校验的决策 token claim
     */
    record Admit(DecisionTokenClaims claims) implements GuardVerdict {

        /**
         * 校验 claim 非空。
         *
         * @throws NullPointerException {@code claims} 为 {@code null}
         */
        public Admit {
            Objects.requireNonNull(claims, "claims");
        }
    }

    /**
     * 拒绝：命令不得签发。
     *
     * @param code 错误码
     * @param detail 排障用的具体说明；{@code null} 表示无补充说明
     */
    record Reject(AiErrorCode code, String detail) implements GuardVerdict {

        /**
         * 校验错误码非空。
         *
         * @throws NullPointerException {@code code} 为 {@code null}
         */
        public Reject {
            Objects.requireNonNull(code, "code");
        }
    }
}
