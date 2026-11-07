/**
 * IAM 装配与 Web 边界：OIDC、SCIM、RBAC/ABAC、TOTP 与授权契约 API。
 *
 * <p><b>职责</b>：把 IAM 契约（{@code core} 的 {@code iam}、{@code iam.spi}、{@code iam.authz}）
 * 装配成可运行的授权服务器与资源服务器组件。
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 与实现模块的 IAM 存储；禁止被 {@code seedj-biz-starter}
 * 反向依赖。装配包内的依赖保持单向：config → 其余实现包，业务包不反向依赖 config。
 */
package cn.byteforce.seedj.starter.iam;
