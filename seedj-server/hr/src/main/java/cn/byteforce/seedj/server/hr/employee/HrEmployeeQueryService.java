package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalQueryRepository;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 员工类型化查询：业务只写字段映射，分页/时点/脱敏由模板提供。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class HrEmployeeQueryService extends TemporalQueryRepository<HrEmployeeView> {

    /**
     * 构造服务。
     *
     * @param store  对象存储
     * @param schema 员工 schema
     */
    public HrEmployeeQueryService(TemporalStore store, ObjectSchema schema) {
        super(store, schema);
    }

    /**
     * 按 ID 查询当前员工。
     *
     * @param id 员工 ID
     * @return 视图
     */
    public Optional<HrEmployeeView> byId(UUID id) {
        return current(id);
    }

    /**
     * 员工列表。
     *
     * @param page 分页
     * @return 视图页
     */
    public Page<HrEmployeeView> list(PageRequest page) {
        return search(new TemporalSearch(schema().objectType(), Map.of(), null, null, null,
                "employee_no", SortDirection.ASC, page));
    }

    @Override
    protected HrEmployeeView map(TemporalRow row) {
        Map<String, Object> fields = readable(row).fields();
        return new HrEmployeeView(row.version().id(), row.version().version(),
                row.version().validFrom(), row.version().validTo(),
                string(fields, "employee_no"), string(fields, "name"), string(fields, "status"),
                date(fields, "hire_date"), string(fields, "employment_type"),
                string(fields, "work_location"), string(fields, "org_unit_id"));
    }

    private static String string(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null ? null : value.toString();
    }

    private static LocalDate date(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return switch (value) {
            case null -> null;
            case LocalDate date -> date;
            case java.sql.Date date -> date.toLocalDate();
            default -> LocalDate.parse(value.toString());
        };
    }
}
