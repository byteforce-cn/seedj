package cn.byteforce.seedj.starter.eis.authz;

/**
 * 远程授权调用失败异常（状态码 + 路径 + 响应文本）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuthzRemoteException extends RuntimeException {

    private final int status;

    private final String path;

    /**
     * 构造异常。
     *
     * @param status 远端状态码
     * @param path   调用路径
     * @param body   响应文本
     */
    public EisAuthzRemoteException(int status, String path, String body) {
        super("授权服务调用失败: " + path + " → HTTP " + status + " " + body);
        this.status = status;
        this.path = path;
    }

    /**
     * 获取远端状态码。
     *
     * @return HTTP 状态码
     */
    public int getStatus() {
        return status;
    }

    /**
     * 获取调用路径。
     *
     * @return 路径
     */
    public String getPath() {
        return path;
    }
}
