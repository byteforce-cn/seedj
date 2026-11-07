/**
 * 注解处理器实现：把 {@code @SeedjObject} record 渲染成 schema、OpenAPI、迁移候选与可选读视图。
 *
 * <p><b>职责</b>：只做编译期代码生成，不参与运行时；生成物落在
 * {@code target/generated-sources} 与 {@code META-INF/seedj}。</p>
 *
 * <p><b>依赖方向</b>：只依赖 JDK 与 {@code core} 的注解/枚举；不依赖 Spring、JDBC
 * 或任何适配器模块，也不会被业务模块以 {@code compile} 依赖引用。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.codegen.processor;
