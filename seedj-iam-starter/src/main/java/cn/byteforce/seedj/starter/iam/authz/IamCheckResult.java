package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.iam.authz.AttributeState;
import java.util.Objects;

/**
 * 权限检查结果：IAM 内部类型，不进入公开 API。
 *
 * <p>{@code /check} 的线格式从这里映射到 {@code CheckResponse}；跨能力的授权判断统一用 {@link
 * cn.byteforce.seedj.security.AuthorizationDecision}。
 *
 * @param allowed 是否允许
 * @param reason 人类可读原因；不得包含属性值全文
 * @param reasonCode 机器可读原因码
 * @param attributeState 属性快照状态
 * @author ByteForce
 * @since 1.0.0
 */
record IamCheckResult(
        boolean allowed, String reason, String reasonCode, AttributeState attributeState) {

    IamCheckResult {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(reasonCode, "reasonCode");
        attributeState = attributeState == null ? AttributeState.NOT_REQUIRED : attributeState;
    }
}
