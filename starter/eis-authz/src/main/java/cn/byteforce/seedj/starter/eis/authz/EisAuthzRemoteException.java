package cn.byteforce.seedj.starter.eis.authz;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * 远程授权调用失败异常（状态码 + 路径 + 响应文本）。
 *
 * <p>状态码语义：远端返回的 HTTP 状态码原样保留；网络层失败为本地判定——
 * 超时 {@code 504}、连接/IO 失败 {@code 503}（消息中同时保留原因，便于与 4xx/5xx 区分）。</p>
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
        this(status, path, body, null);
    }

    /**
     * 构造异常（保留底层原因）。
     *
     * @param status 远端状态码；网络层失败为本地判定（超时 {@code 504} / 连接·IO 失败 {@code 503}）
     * @param path   调用路径
     * @param body   响应文本或本地失败说明
     * @param cause  底层原因；可空
     */
    public EisAuthzRemoteException(int status, String path, String body, Throwable cause) {
        super("授权服务调用失败: " + path + " → HTTP " + status + " " + body, cause);
        this.status = status;
        this.path = path;
    }

    /**
     * 由网络层失败构造异常（fail-closed：超时与连接/IO 失败都不得静默降级）。
     *
     * @param path  调用路径
     * @param cause 网络层失败（{@code ResourceAccessException} 或响应体读取中断的包装异常）
     * @return 异常：超时 {@code status=504}，连接/IO 失败 {@code status=503}
     */
    public static EisAuthzRemoteException resourceFailure(String path, Throwable cause) {
        String detail = detail(cause);
        if (isTimeout(cause)) {
            return new EisAuthzRemoteException(504, path, "超时（" + detail + "）", cause);
        }
        return new EisAuthzRemoteException(503, path, "连接/IO 失败（" + detail + "）", cause);
    }

    /**
     * 判断失败是否属于网络层（失败链上含 {@link IOException}：连接拒绝、超时、传输中断）。
     *
     * <p>响应体停摆时 JDK 取消订阅、Spring 以普通 {@code RestClientException} 包装，
     * 失败链上已无超时类型，但仍属有界网络失败；非网络失败（如内容协商失败）不应被误标。</p>
     *
     * @param failure 失败
     * @return 是否网络层失败
     */
    static boolean isNetworkFailure(Throwable failure) {
        for (Throwable current = failure; current != null && current != current.getCause();
                current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTimeout(Throwable cause) {
        for (Throwable current = cause; current != null && current != current.getCause();
                current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String detail(Throwable cause) {
        Throwable deepest = cause;
        while (deepest != null && deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        if (deepest == null) {
            return "未知原因";
        }
        String message = deepest.getMessage();
        return message == null || message.isBlank() ? deepest.getClass().getSimpleName() : message;
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
