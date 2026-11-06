/**
 * 构建期 schema 声明注解。
 *
 * <p><b>职责</b>：业务侧用 {@link cn.byteforce.seedj.codegen.SeedjObject} 在 record 上声明
 * 对象 schema，{@code seedj-codegen} 在编译期生成 {@code ObjectSchema}、OpenAPI 片段与
 * 迁移候选。注解是契约的一部分，留在 {@code core}；生成器本身是纯构建期工件，不进入
 * 运行时 classpath。</p>
 *
 * <p><b>依赖方向</b>：本包只依赖 {@code java.lang} 与 {@code domain} 的枚举/接口；
 * 不依赖 Spring、JDBC 或任何适配器。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.codegen;
