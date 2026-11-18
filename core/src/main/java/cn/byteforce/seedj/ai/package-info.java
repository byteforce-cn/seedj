/**
 * AI 命令网关的契约层：命令指纹、动作分类等由账本、token 与审批守卫共用的值类型。
 *
 * <p><b>职责</b>：定义“AI 发起的命令如何被描述、比对与分级”的纯值契约，不含任何执行、存储或 网络逻辑；调用方（网关、守卫、账本）依赖这里的类型，而不是各自复制一套指纹算法。
 *
 * <p><b>依赖方向</b>：只允许 JDK、provided 的 Jackson 3 与 {@code cn.byteforce.seedj.core} 内的 工具类；不依赖
 * Spring、{@code java.sql}/{@code javax.sql} 或任何实现模块。指纹算法复用 {@code CanonicalJsonHelper} 与 {@code
 * IdempotencyFingerprint}，保证与审计链、幂等键同一口径。
 */
package cn.byteforce.seedj.ai;
