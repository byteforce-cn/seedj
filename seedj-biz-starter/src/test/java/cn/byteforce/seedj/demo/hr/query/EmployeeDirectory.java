package cn.byteforce.seedj.demo.hr.query;

import cn.byteforce.seedj.demo.hr.domain.EmployeeId;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 员工目录读模型（内存实现，生产环境换成投影表）。
 *
 * <p>写入用"按标识整体覆盖"（upsert），因此重复投递同一事件不会产生重复行——
 * at-least-once 下的幂等由数据结构本身保证，而不是靠调用方记得去重。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EmployeeDirectory {

    private static final Comparator<EmployeeView> BY_EMPLOYEE_NO =
            Comparator.comparing(EmployeeView::employeeNo);

    private final ConcurrentMap<EmployeeId, EmployeeView> rows = new ConcurrentHashMap<>();

    public void upsert(EmployeeView view) {
        rows.put(view.employeeId(), view);
    }

    public Optional<EmployeeView> find(EmployeeId employeeId) {
        return Optional.ofNullable(rows.get(employeeId));
    }

    public List<EmployeeView> list() {
        return rows.values().stream().sorted(BY_EMPLOYEE_NO).toList();
    }
}
