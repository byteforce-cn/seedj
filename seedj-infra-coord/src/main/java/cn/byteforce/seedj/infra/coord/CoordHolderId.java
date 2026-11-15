package cn.byteforce.seedj.infra.coord;

import java.util.UUID;

/**
 * 锁持有者标识。
 *
 * <p>必须是「每个实例唯一、重启后变化」的值：同一进程内的两次获取共享它（可区分不同实例）， 进程重启后旧租约即便未过期也不会被误认为本实例持有。默认值不写死主机名，便于容器/本地两种环境。
 */
final class CoordHolderId {

    private static final String FALLBACK_HOST = "local";

    private CoordHolderId() {}

    static String of(CoordProperties properties) {
        String configured = properties.getLock().getHolderId();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return derive(System.getenv("HOSTNAME"));
    }

    static String derive(String host) {
        String node = host == null || host.isBlank() ? FALLBACK_HOST : host;
        return node
                + ":"
                + ProcessHandle.current().pid()
                + ":"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
