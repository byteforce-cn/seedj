/**
 * IM 存储端口（{@code cn.byteforce.seedj.im.spi}）的 PostgreSQL 实现。
 *
 * <p>表由 {@link cn.byteforce.seedj.infra.jdbc.im.ImSchemaCapability} 在启动期创建，迁移 scope 为 {@code
 * im}；装配发生在 {@code seedj-im-starter}，本包不依赖 starter 也不依赖 Netty。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.infra.jdbc.im;
