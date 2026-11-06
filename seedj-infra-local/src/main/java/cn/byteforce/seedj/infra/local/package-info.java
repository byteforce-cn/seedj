/**
 * 本地基础设施实现：内存 / 文件 / BouncyCastle 适配器。
 *
 * <p><b>职责</b>：为 PKI、Transit、ID、EventBus、锁、审计、命令、对象存储与工作流提供
 * 不依赖外部中间件的参照实现，供测试、本地演示与契约测试使用。</p>
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 契约与 JDK；禁止依赖 JDBC、starter 与宿主模块。
 * PostgreSQL 实现见 {@code seedj-jdbc-postgresql}。</p>
 */
package cn.byteforce.seedj.infra.local;
