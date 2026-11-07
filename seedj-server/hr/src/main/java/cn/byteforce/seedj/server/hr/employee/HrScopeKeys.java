package cn.byteforce.seedj.server.hr.employee;

import static cn.byteforce.seedj.core.security.ScopeKeyType.LIST_STRING;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeKey;
import java.util.List;

/**
 * HR 读路径使用的请求事实 key。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrScopeKeys {

    /** 调用方可读的组织单元集合。 */
    public static final ScopeKey<List<String>> ORG_UNITS = new ScopeKey<>("org.units", LIST_STRING);

    private static final String ORG_UNITS_ATTRIBUTE = "org_units";

    private HrScopeKeys() {}

    /** 从调用方属性解析组织单元；缺失或非集合时返回空列表。 */
    public static List<String> callerOrgUnits(Caller caller) {
        return caller.attributeStrings(ORG_UNITS_ATTRIBUTE);
    }
}
