package cn.byteforce.seedj.starter.eis.core;

import java.util.Optional;
import java.util.UUID;

/**
 * 操作人解析 SPI：从当前请求环境解析 EIS 操作人（UUID）。
 *
 * <p>HTTP 命令端点要求已认证主体；缺省实现
 * {@link cn.byteforce.seedj.starter.eis.core.web.SecurityContextEisActorResolver}
 * 读取 Spring Security 上下文的认证名（JWT 场景即 {@code sub}）。
 * 内部命令（如登录签发会话）不走 HTTP 端点，由服务端直接调用运行时并显式指定 actor。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface EisActorResolver {

    /**
     * 解析当前操作人。
     *
     * @return 操作人 UUID；未认证/不可解析为空（端点将响应 EIS-401-001）
     */
    Optional<UUID> currentActor();
}
