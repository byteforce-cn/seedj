package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.infra.local.security.InMemorySecurityAuditSink;

/**
 * IAM 侧的内存审计实现：保留历史类型名，行为委托给通用的 {@link InMemorySecurityAuditSink}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class InMemoryIamAuditSink extends InMemorySecurityAuditSink {

    public InMemoryIamAuditSink() {
        super();
    }

    public InMemoryIamAuditSink(int capacity) {
        super(capacity);
    }
}
