package cn.byteforce.seedj.iam.authz;

import java.util.Locale;

/**
 * 属性快照状态。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum AttributeState {

    FRESH,
    STALE,
    MISSING,
    SOURCE_UNAVAILABLE,
    NOT_REQUIRED;

    /**
     * 线格式名称（小写）。
     *
     * @return 小写名称
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
