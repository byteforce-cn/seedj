/**
 * IAM 契约模块：用户、组、角色、策略、SCIM 模型与授权值类型。
 *
 * <p><b>职责</b>：把 IAM 的跨能力契约从 {@code core} 拆成可独立演进的制品；starter、
 * 适配器与宿主共享同一套类型，不再通过 {@code core} 的发布节奏被动升级。
 *
 * <p><b>依赖方向</b>：只允许 {@code core} 的授权/安全值类型与 JDK；不依赖 Spring、
 * JDBC 或任何实现模块。
 */
package cn.byteforce.seedj.iam;
