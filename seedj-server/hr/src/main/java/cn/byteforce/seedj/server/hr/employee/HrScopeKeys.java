package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.security.ScopeKey;

import java.util.List;

import static cn.byteforce.seedj.core.security.ScopeKeyType.LIST_STRING;

/**
 * HR 读路径使用的请求事实 key。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrScopeKeys {

    /** 调用方可读的组织单元集合。 */
    public static final ScopeKey<List<String>> ORG_UNITS =
            new ScopeKey<>("org.units", LIST_STRING);

    private HrScopeKeys() {
    }
}
