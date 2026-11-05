package cn.byteforce.seedj.core;

/**
 * 基础设施错误的分类，供调用方决定"重试 / 修参数 / 改配置"。
 *
 * <p>三个基础设施 SPI（pki / transit / eventbus / id）共用这一套分类，
 * 业务方只需写一次 {@code catch} 逻辑。
 */
public enum ErrorCategory {

    /** 调用方参数或状态不合法，原样重试无意义。 */
    CLIENT_ERROR,

    /** 配置错误或能力不支持，需要改配置 / 换 provider。 */
    CONFIG_ERROR,

    /** 后端暂时不可用，可按退避策略重试。 */
    BACKEND_ERROR,

    /** 超出配额或批量上限。 */
    LIMIT_EXCEEDED
}
