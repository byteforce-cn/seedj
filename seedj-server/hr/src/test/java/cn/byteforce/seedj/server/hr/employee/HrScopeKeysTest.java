package cn.byteforce.seedj.server.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.security.Caller;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 调用方组织属性只在一个地方解析，业务代码不再读裸 Map。 */
class HrScopeKeysTest {

    @Test
    @DisplayName("org_units 属性解析为不可变字符串列表")
    void callerOrgUnitsReturnsTypedList() {
        Caller caller = caller(Map.of("org_units", List.of("D01", "D02")));

        assertThat(HrScopeKeys.callerOrgUnits(caller)).containsExactly("D01", "D02");
    }

    @Test
    @DisplayName("缺失或非集合属性返回空列表，不抛异常")
    void missingOrScalarReturnsEmpty() {
        assertThat(HrScopeKeys.callerOrgUnits(caller(Map.of()))).isEmpty();
        assertThat(HrScopeKeys.callerOrgUnits(caller(Map.of("org_units", "D01")))).isEmpty();
    }

    private static Caller caller(Map<String, Object> attributes) {
        return new Caller(
                "018f2e3a-0000-7000-8000-000000000021",
                "tester",
                "default",
                Set.of(),
                Set.of("hr.employee.write"),
                Set.of(),
                Set.of(Caller.DEFAULT_AMR),
                Caller.DEFAULT_ACR,
                attributes);
    }
}
