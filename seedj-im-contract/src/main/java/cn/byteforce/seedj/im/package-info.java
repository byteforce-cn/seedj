/**
 * IM 契约层：会话、成员、消息与游标的值类型。
 *
 * <p>只依赖 Java 标准库与 {@code core}，不依赖 Spring、Netty 或任何存储实现；协议与传输在 {@code seedj-im-starter}，存储实现在
 * {@code seedj-infra-local} / {@code seedj-jdbc-postgresql}。
 *
 * @author ByteForce
 * @since 1.0.0

 * <p><b>职责</b>：本模块是 {@code im} 能力的跨模块契约，服务实现方与消费方
 * 以它为准，不再依赖 {@code core} 的发布节奏。
 *
 * <p><b>依赖方向</b>：只允许 {@code core} 的值类型与 JDK；不依赖 Spring、JDBC 或任何
 * 实现模块。
 */
package cn.byteforce.seedj.im;
