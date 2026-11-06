package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.read.DataScopeResolver;

import java.util.List;

/**
 * 员工行范围：只能看 {@code org.units} 内的组织单元；缺失表示没有可见单位。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrOrgUnitScopeResolver implements DataScopeResolver {

    @Override
    public boolean supports(String objectType) {
        return "employee".equals(objectType);
    }

    @Override
    public ScopePredicate resolve(String objectType, Caller caller, ScopeContext context) {
        List<String> units = context.get(HrScopeKeys.ORG_UNITS).orElse(List.of());
        return units.isEmpty() ? ScopePredicate.none()
                : ScopePredicate.in("org_unit_id", units);
    }
}
