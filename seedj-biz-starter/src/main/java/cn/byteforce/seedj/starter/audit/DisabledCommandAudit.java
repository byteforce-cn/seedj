package cn.byteforce.seedj.starter.audit;

import cn.byteforce.seedj.cqrs.Command;
import cn.byteforce.seedj.cqrs.CommandAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审计被显式关闭时的 seam：不写任何东西，但对每个已标注的命令类 WARN 一次。
 *
 * <p>{@code seedj.audit.enabled=false} 是允许的选择，只是不允许悄悄发生。</p>
 */
public final class DisabledCommandAudit implements CommandAudit {

    private static final Logger log = LoggerFactory.getLogger(DisabledCommandAudit.class);

    private final Set<Class<?>> warned = ConcurrentHashMap.newKeySet();

    @Override
    public void accepted(Command<?> command, String resourceId) {
        warnOnce(command);
    }

    @Override
    public void rejected(Command<?> command, String resourceId, RuntimeException error) {
        warnOnce(command);
    }

    private void warnOnce(Command<?> command) {
        Class<?> type = command.getClass();
        if (AuditedCommands.find(type).isEmpty() || !warned.add(type)) {
            return;
        }
        log.warn("seedj audit is disabled but command {} is annotated with @Audited; "
                + "no audit record will be written", type.getName());
    }
}
