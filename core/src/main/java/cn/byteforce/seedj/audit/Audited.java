package cn.byteforce.seedj.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一条命令需要写入审计链。
 *
 * <p>业务方只需标注注解：操作人、序号、哈希、事务与存储由框架在 {@link cn.byteforce.seedj.cqrs.CommandAgent} 的事务块内完成。
 *
 * <pre>{@code
 * @Audited(action = "employee.onboarded", resourceType = "employee")
 * public record OnboardEmployeeCommand(EmployeeId employeeId, String employeeNo)
 *         implements Command<EmployeeId> {}
 * }</pre>
 *
 * <p>{@code action} 显式给出：动作名进入哈希链，不能随类名漂移。 {@code resourceType} 留空时由命令类简单名推导；{@code resourceId} 恒为
 * {@code command.aggregateId()}，不提供属性覆盖。
 *
 * <p><b>审计只记录已受理的状态变更</b>：命令回滚则审计行不存在。被拒绝的命令 走独立的安全审计通道（{@code SecurityAuditSink}），不进入哈希链。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 稳定动作名，建议 {@code 聚合.动作} 小写点分格式（如 {@code employee.onboarded}）。 */
    String action();

    /** 资源类型；留空时由命令类简单名去掉 {@code Command} 后缀并转小写。 */
    String resourceType() default "";
}
