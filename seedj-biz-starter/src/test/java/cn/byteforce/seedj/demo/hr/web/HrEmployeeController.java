package cn.byteforce.seedj.demo.hr.web;

import cn.byteforce.seedj.demo.hr.app.HrCommandService;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.demo.hr.query.EmployeeView;
import cn.byteforce.seedj.demo.hr.query.HrQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工入职的命令端点 + 员工目录的查询端点。
 *
 * <p>命令返回标识、不返回读模型；查询不触发命令。前端拿到标识后自己回查，
 * 这样读侧延迟（投影追平所需时间）不会伪装成"写失败"。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/hr/employees")
public final class HrEmployeeController {

    private final HrCommandService commands;

    private final HrQueryService queries;

    public HrEmployeeController(HrCommandService commands, HrQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    /**
     * 员工入职。
     */
    @PostMapping
    public ResponseEntity<OnboardedResponse> onboard(@RequestBody OnboardRequest request) {
        EmployeeId employeeId = commands.onboard(
                request.employeeNo(), request.name(), request.department(),
                request.position(), request.hireDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OnboardedResponse(employeeId.value()));
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

    /**
     * 入职请求体。
     */
    public record OnboardRequest(
            String employeeNo,
            String name,
            String department,
            String position,
            LocalDate hireDate) {
    }

    /**
     * 入职响应体：只回标识。
     */
    public record OnboardedResponse(String employeeId) {
    }
}
