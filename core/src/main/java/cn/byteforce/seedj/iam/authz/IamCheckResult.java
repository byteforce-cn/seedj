package cn.byteforce.seedj.iam.authz;

import java.util.Objects;

/**
 * 权限检查结果。
 *
 * @param allowed        是否允许
 * @param reason         人类可读原因；不得包含属性值全文
 * @param reasonCode     机器可读原因码
 * @param attributeState 属性快照状态
 * @author ByteForce
 * @since 1.0.0
 */
public record IamCheckResult(boolean allowed, String reason, String reasonCode,
                             AttributeState attributeState) {

    public IamCheckResult {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(reasonCode, "reasonCode");
        attributeState = attributeState == null ? AttributeState.NOT_REQUIRED : attributeState;
    }
}
