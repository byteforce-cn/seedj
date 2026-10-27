/**
 * EIS 领域内核：业务代码唯一需要阅读的包。
 *
 * <p><b>八个概念</b>：{@link cn.byteforce.seedj.starter.eis.domain.AggregateRoot}、
 * {@link cn.byteforce.seedj.starter.eis.domain.EventApplier}、
 * {@code raise}、{@code replay}、
 * {@link cn.byteforce.seedj.starter.eis.domain.AggregateRepository}、
 * {@code loadOrThrow}、{@code save}、{@code rehydrate}。</p>
 *
 * <p><b>依赖白名单</b>：本包只允许依赖 JDK 与 {@code cn.byteforce.seedj.core}
 * （统一异常契约）。不得引入 Spring / Jackson / JDBC——适配器在
 * {@code starter-eis-core} 与后续事件脊柱模块中实现，由构建门禁与评审共同守护。</p>
 *
 * <p><b>状态只能由事件改变</b>：命令方法调用 {@code raise} 修改状态并登记事件；
 * {@code replay} 只用于历史重放。直接改字段的后果是「内存里成功、库里什么都没有」，
 * 这类缺陷由 {@code AggregateAssertions.assertReplayable} 在测试期捕捉（后续版本提供）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.starter.eis.domain;
