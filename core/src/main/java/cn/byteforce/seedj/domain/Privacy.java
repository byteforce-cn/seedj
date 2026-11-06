package cn.byteforce.seedj.domain;

/**
 * 字段隐私层级：读投影的默认可见性由它决定，字段策略只能在此基础上收窄或按动作放宽。
 *
 * <p>顺序为 {@code PUBLIC < INTERNAL < SENSITIVE < RESTRICTED}。默认渲染：
 * {@code PUBLIC/INTERNAL → FULL}，{@code SENSITIVE/RESTRICTED → HIDDEN}；
 * 策略允许 {@code read.masked} 时敏感字段可降为打码，允许 {@code read} 时受限字段可完整读取。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum Privacy {

    /** 公开信息，任何已认证调用方都可读。 */
    PUBLIC,

    /** 内部信息，已认证调用方默认可读。 */
    INTERNAL,

    /** 敏感信息，默认隐藏；需要 {@code read.masked} 才能看到打码值。 */
    SENSITIVE,

    /** 受限信息，默认隐藏；需要显式 {@code read} 才能看到完整值。 */
    RESTRICTED
}
