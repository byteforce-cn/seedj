package cn.byteforce.seedj.provider.pki;

/** 证书用途：映射到 KU/EKU。 */
public enum CertificateUsage {
    /** 服务端证书：KU digitalSignature+keyEncipherment，EKU serverAuth。 */
    SERVER,
    /** 客户端证书：KU digitalSignature，EKU clientAuth。 */
    CLIENT,
    /** 双向用途：KU digitalSignature+keyEncipherment，不声明 EKU。 */
    BOTH
}
