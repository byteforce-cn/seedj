package cn.byteforce.seedj.starter.eis.core.web;

import cn.byteforce.seedj.starter.eis.core.EisActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * 默认操作人解析：读取 Spring Security 上下文的已认证主体名（JWT 场景 = {@code sub}）。
 *
 * <p>主体名为 UUID 串时解析成功；匿名/解析失败返回空（端点响应 EIS-401-001）。
 * 应用可定义同类型 Bean 接管（如从租户上下文映射）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class SecurityContextEisActorResolver implements EisActorResolver {

    private static final Logger log = LoggerFactory.getLogger(SecurityContextEisActorResolver.class);

    @Override
    public Optional<UUID> currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if ("anonymousUser".equals(principal)) {
            return Optional.empty();
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(name));
        } catch (IllegalArgumentException ex) {
            log.warn("已认证主体名不是合法 UUID，无法解析 EIS 操作人: {}", name);
            return Optional.empty();
        }
    }
}
