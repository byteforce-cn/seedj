package cn.byteforce.seedj.demo.hr.query;

import cn.byteforce.seedj.cqrs.QueryAgent;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 读模型的查询入口：每个读模型一个 {@link QueryAgent}（缓存/鉴权粒度不同）。
 *
 * <p>查询不加载聚合、不读事件流，也不参与锁与事务。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrQueryService {

    private final EmployeeDirectory directory;

    private final QueryAgent<Optional<EmployeeView>> byIdAgent;

    private final QueryAgent<List<EmployeeView>> listAgent;

    public HrQueryService(
            EmployeeDirectory directory,
            QueryAgent<Optional<EmployeeView>> byIdAgent,
            QueryAgent<List<EmployeeView>> listAgent) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.byIdAgent = Objects.requireNonNull(byIdAgent, "byIdAgent");
        this.listAgent = Objects.requireNonNull(listAgent, "listAgent");
    }

    public Optional<EmployeeView> byId(EmployeeId employeeId) {
        Objects.requireNonNull(employeeId, "employeeId");
        return byIdAgent.execute(() -> directory.find(employeeId));
    }

    public List<EmployeeView> list() {
        return listAgent.execute(directory::list);
    }
}
