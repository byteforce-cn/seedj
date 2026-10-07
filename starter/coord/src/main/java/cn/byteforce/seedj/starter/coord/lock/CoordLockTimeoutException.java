package cn.byteforce.seedj.starter.coord.lock;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;

/**
 * 获取锁超时 / 等待被中断。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordLockTimeoutException extends CoordException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 详情（锁名 / 等待时长 / 持有者）
     */
    public CoordLockTimeoutException(String message) {
        super(ErrorCode.DEADLINE_EXCEEDED, message);
    }

    /**
     * @param message 详情
     * @param cause   原因
     */
    public CoordLockTimeoutException(String message, Throwable cause) {
        super(ErrorCode.DEADLINE_EXCEEDED, message, cause);
    }
}
