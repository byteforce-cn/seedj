package cn.byteforce.seedj.starter.audit;

import cn.byteforce.seedj.audit.Audited;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@code @Audited} 的解析与缺省推导。反射结果按命令类缓存。 */
final class AuditedCommands {

    private static final Map<Class<?>, Optional<Audited>> CACHE = new ConcurrentHashMap<>();

    private AuditedCommands() {}

    static Optional<Audited> find(Class<?> commandType) {
        return CACHE.computeIfAbsent(
                commandType, type -> Optional.ofNullable(type.getAnnotation(Audited.class)));
    }

    static String resourceType(Audited audited, Class<?> commandType) {
        if (!audited.resourceType().isBlank()) {
            return audited.resourceType();
        }
        String simple = commandType.getSimpleName();
        if (simple.endsWith("Command") && simple.length() > "Command".length()) {
            simple = simple.substring(0, simple.length() - "Command".length());
        }
        return simple.toLowerCase(Locale.ROOT);
    }
}
