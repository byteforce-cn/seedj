/**
 * DecisionToken 的签发、JWKS 发布与执行侧校验：执行侧准入的唯一凭据。
 *
 * <p><b>职责</b>：把 {@code cn.byteforce.seedj.ai} 的 {@code DecisionTokenClaims} 编成标准 JWS（{@code
 * alg=EdDSA}、{@code typ=agent-decision+jws}、{@code kid} 定位密钥），发布只含公钥的 JWKS，并按 spec §7.3 的
 * 第 1–8、10 条（外加一条受众校验）把「token + 请求侧事实」判成 {@code GuardVerdict}。第 9 条 {@code
 * policy_stale} 在 T0 <b>不可求值</b>：{@code GuardContext} 不携带策略 head，T0 也不做 {@code Evaluate}（计划
 * R7），等 U8 落地后在 T1 补。本包不做授权决策，也不认识命令目录：动作分类只信 {@code ToolRegistry}，命令比对只信
 * 调用方给的请求侧事实。
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 的 AI 契约、Nimbus 的 JWS 结构（header、标准签名输入、compact
 * 序列化）与 BouncyCastle 的 Ed25519 公钥推导；禁止依赖 {@code seedj-jdbc-postgresql} 的存储实现，也禁止被宿主的
 * 业务包反向依赖。
 */
package cn.byteforce.seedj.starter.ai.token;
