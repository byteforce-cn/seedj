/**
 * Transit 全局契约（业务入口 + provider 实现契约）。
 *
 * <h2>部署形态</h2>
 *
 * 内置实现同时使用内存与磁盘：密钥材料持久化到 {@code seedj.transit.state-file} （默认 {@code
 * ${java.io.tmpdir}/seedj-transit-state.properties}）。 同机多个服务指向同一文件，即共享同一套密钥与版本历史。
 *
 * <h2>线程安全</h2>
 *
 * 所有实现必须线程安全。内部连接池、缓存由实现自行管理，不得要求调用方同步。
 *
 * <h2>SPI 返回对象的构造器可见性</h2>
 *
 * 所有 Result 对象（如 Ciphertext、DataKey、BatchResult 等）的构造器为 public， 但严格声明：仅供 SPI 实现方使用，调用方不得自行构造。调用方只能从
 * SPI 方法获取实例。
 *
 * <h2>幂等性与空值语义</h2>
 *
 * <ul>
 *   <li>createKey: 同名同参幂等。同参判定基于“解析后的有效配置”。若不一致抛 KEY_ALREADY_EXISTS。
 *   <li>rotateKey: 非幂等。每次成功调用产生新版本。
 *   <li>deleteKey: 幂等。删除不存在或已标记删除的 key 返回成功。
 *   <li>close: 幂等。第二次及以后调用不抛异常；关闭后除 close() 外，所有方法抛 PROVIDER_CLOSED。
 * </ul>
 *
 * <h2>错误模型与边界</h2>
 *
 * <ul>
 *   <li>UNSUPPORTED_OPERATION: 实现能力限制，非参数错误。调用方不应修改参数重试。
 *   <li>TYPE_MISMATCH: 支持该操作，但 key 类型与操作或 algorithm 不匹配。
 *   <li>INVALID_CONTEXT: 派生密钥要求 context 但请求缺失，或 context 与密钥的 derived 配置不匹配。
 *   <li>INVALID_CIPHERTEXT: 密文格式非法、无法解析版本或编码。
 *   <li>DECRYPTION_FAILED: 密文格式合法但解密失败（如认证标签失败）。
 * </ul>
 *
 * <h2>算法对称性与推断规则</h2>
 *
 * <ul>
 *   <li>SignRequest.algorithm 为 null 时使用密钥默认算法。VerifyRequest.algorithm 同理。
 *   <li>VerifyRequest.keyVersion 为 null 时，实现应使用最新版本验签；若未知旧版本，可遍历尝试。
 * </ul>
 *
 * <h2>rewrap 语义（强约束）</h2>
 *
 * rewrap 的唯一语义是“重新包装到最新版本”。不支持指定目标版本或源版本。
 *
 * <h2>批量语义</h2>
 *
 * <ul>
 *   <li>items 不允许 null，且不允许包含 null 元素；空列表合法，返回空列表。
 *   <li>仅对请求级失败抛 TransitException；单项失败进入 BatchResult.errorCode。
 * </ul>
 *
 * <h2>敏感数据与日志脱敏（全链路）</h2>
 *
 * 所有 Request/Result 对象的 toString() 不得输出明文、密文、context、签名、密钥材料。
 *
 * <h2>编码与校验规则</h2>
 *
 * 所有 context、signature、hmac 必须是标准 Base64（RFC 4648 带填充），严格拒绝空白字符。keyName 与 reference 拒绝首尾空白。
 */
package cn.byteforce.seedj.transit;
