package cn.byteforce.seedj.provider.pki;

/** TLS 对端证书策略，对应 {@code want}/{@code need} 的语义。 */
public enum ClientAuth {
    /** 不请求客户端证书。 */
    NONE,
    /** 请求但不强制（对应 JSSE {@code want}）。 */
    OPTIONAL,
    /** 强制客户端证书（对应 JSSE {@code need}）。 */
    REQUIRED
}
