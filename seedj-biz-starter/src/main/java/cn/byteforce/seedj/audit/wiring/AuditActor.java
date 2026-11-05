package cn.byteforce.seedj.audit.wiring;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 审计操作人解析：认证主体名，缺失时回落 {@code system} / {@code anonymous}，永不为 null。
 */
final class AuditActor {

    private AuditActor() {
    }

    static String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "system";
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return "anonymous";
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? "system" : name;
    }
}
