package cn.byteforce.seedj.core.security;

/**
 * 安全审计通道。
 *
 * <p>实现方不得让审计失败影响调用方的业务动作（拒绝/认证失败的记录本身 不应把请求从"拒绝"变成"500"）；持久化失败由实现自行记录日志或指标。
 *
 * <p>由 biz 与 iam 两侧共同使用：IAM 记录凭证变更，资源服务器记录 401/403。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface SecurityAuditSink {

    /** 记录一条安全事件；实现必须容忍并发调用。 */
    void record(SecurityAuditEvent event);
}
