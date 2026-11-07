package cn.byteforce.seedj.demo.hr.web;

import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.demo.hr.query.EmployeeView;
import cn.byteforce.seedj.demo.hr.query.HrQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 员工目录的查询端点。
 *
 * <p>命令写入统一走 {@code POST /commands/hr.employee.onboard}，本模块不再自写命令
 * Controller；查询不触发命令。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/hr/employees")
public final class HrEmployeeController {

    private final HrQueryService queries;

    public HrEmployeeController(HrQueryService queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /**
     * 按标识查询员工目录。
     */
    @GetMapping("/{employeeId}")
    public ResponseEntity<EmployeeView> byId(@PathVariable String employeeId) {
        return queries.byId(EmployeeId.of(employeeId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 员工目录列表。
     */
    @GetMapping
    public List<EmployeeView> list() {
        return queries.list();
    }
}
