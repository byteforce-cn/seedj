/**
 * IAM 宿主：授权服务器、SCIM/RBAC 端点与 OIDC 客户端。
 *
 * <p><b>职责</b>：作为可运行宿主，组合 starter 与实现模块，提供 HTTP 入口与运行配置。</p>
 *
 * <p><b>依赖方向</b>：只依赖 starter 与实现模块；禁止被任何库模块依赖。</p>
 */
package cn.byteforce.seedj.server.iam;
