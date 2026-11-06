/**
 * 契约基础类型：错误码、迁移、基础设施能力、请求上下文与时间轴模型。
 *
 * <p><b>职责</b>：为所有能力提供零 Spring、零数据库的公共契约。</p>
 *
 * <p><b>依赖方向</b>：只允许 JDK、provided 的 Jackson 3 与 SLF4J；禁止依赖 Spring、
 * {@code java.sql}/{@code javax.sql} 与任何实现模块。规则由 {@code ArchitectureTest}
 * 与 {@code CorePurityTest} 执行，决策见 ADR-0010。</p>
 */
package cn.byteforce.seedj.core;
