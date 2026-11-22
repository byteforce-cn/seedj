/**
 * IAM 纯逻辑引擎：身份与授权判定、TOTP/MFA、SCIM 用例。
 *
 * <p><b>职责</b>：把这些用例从 Spring 装配中剥离，让命令行工具、批处理与其它框架宿主可以复用；模块内不出现
 * {@code @Component}/{@code @Configuration}，也不读写数据库。
 *
 * <p><b>依赖方向</b>：只依赖 {@code core} 与 {@code seedj-iam-contract}；由 {@code seedj-iam-starter}
 * 装配，适配器提供存储 SPI 实现。{@code ArchitectureTest} 用 “无 Spring / 无 JDBC” 规则守着这条边界。
 */
package cn.byteforce.seedj.iam.engine;
