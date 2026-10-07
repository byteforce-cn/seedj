package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;

/**
 * 无可用实例（服务名命中不到任何注册实例，或过滤后为空；C6：不静默直连）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordNoAvailableInstanceException extends CoordException {

    private static final long serialVersionUID = 1L;

    /**
     * @param serviceName 服务名
     */
    public CoordNoAvailableInstanceException(String serviceName) {
        super(ErrorCode.UNAVAILABLE, "无可用实例: service=" + serviceName);
    }

    /**
     * @param serviceName 服务名
     * @param reason      附加上下文（如过滤条件）
     */
    public CoordNoAvailableInstanceException(String serviceName, String reason) {
        super(ErrorCode.UNAVAILABLE, "无可用实例: service=" + serviceName + "（" + reason + "）");
    }
}
