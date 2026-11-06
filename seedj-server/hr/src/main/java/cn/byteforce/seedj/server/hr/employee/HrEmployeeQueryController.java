package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

/**
 * 员工读面：业务自有查询入口，需要 {@code hr.employee.read} 权限。
 *
 * <p>通用 {@code /objects} 保留为内部/服务面；业务前端使用本控制器，读权限与视图形状都由
 * HR 自己拥有。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/hr/employees")
public class HrEmployeeQueryController {

    private final HrEmployeeQueryService service;

    /**
     * 构造控制器。
     *
     * @param service 查询服务
     */
    public HrEmployeeQueryController(HrEmployeeQueryService service) {
        this.service = service;
    }

    /**
     * 按 ID 查询。
     *
     * @param id 员工 ID
     * @return 视图
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_hr.employee.read')")
    public HrEmployeeView byId(@PathVariable UUID id) {
        return service.byId(id)
                .orElseThrow(() -> new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                        "employee not found"));
    }

    /**
     * 列表。
     *
     * @param page 页码
     * @param size 每页条数
     * @return 视图页
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_hr.employee.read')")
    public EmployeePage list(@RequestParam(value = "page", defaultValue = "1") int page,
                             @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<HrEmployeeView> result = service.list(new PageRequest(page, size));
        return new EmployeePage(result.items(), result.page(), result.size(), result.total());
    }

    /**
     * 员工分页响应。
     *
     * @param items 当前页
     * @param page  页码
     * @param size  每页条数
     * @param total 总数
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmployeePage(List<HrEmployeeView> items, int page, int size, long total) {}
}
