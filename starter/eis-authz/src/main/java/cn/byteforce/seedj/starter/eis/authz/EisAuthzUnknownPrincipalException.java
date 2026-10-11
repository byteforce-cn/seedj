package cn.byteforce.seedj.starter.eis.authz;

/**
 * 主体不存在异常（HTTP 面映射 404 / EIS-404-001）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuthzUnknownPrincipalException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param ref 主体引用
     */
    public EisAuthzUnknownPrincipalException(String ref) {
        super("主体不存在: " + ref);
    }
}
