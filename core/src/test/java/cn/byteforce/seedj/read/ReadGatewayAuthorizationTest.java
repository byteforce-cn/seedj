package cn.byteforce.seedj.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 读路径与命令共用同一个授权决策点；字段策略仍然生效。 */
class ReadGatewayAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static final Clock CLOCK = () -> NOW;

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final Caller CALLER =
            new Caller(
                    "u-1",
                    "alice",
                    "default",
                    Set.of(),
                    Set.of("hr.employee.read"),
                    Set.of(),
                    Set.of("pwd"),
                    "urn:seedj:acr:pwd",
                    Map.of());

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT)
                .required()
                .field("name", FieldType.TEXT)
                .field("contact", FieldType.TEXT);
        return modules.freeze();
    }

    private static TemporalRow row() {
        return new TemporalRow(
                "employee",
                new ObjectVersion(ID, 1, NOW, null, NOW, null, UUID.randomUUID(), NOW, null, null),
                Map.of("employee_no", "EMP-000001", "name", "张三", "contact", "13800138000"));
    }

    private static TemporalStore store(TemporalRow row) {
        return new TemporalStore() {
            @Override
            public Optional<TemporalRow> find(
                    String objectType, UUID id, TemporalQuery query, ScopePredicate scope) {
                return Optional.ofNullable(row);
            }

            @Override
            public AppendResult append(AppendRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
                return new Page<>(List.of(row), 1, 1, 20);
            }

            @Override
            public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
                return List.of(row);
            }
        };
    }

    private static DataScopeResolver allScope() {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(
                    String objectType,
                    Caller caller,
                    cn.byteforce.seedj.core.security.ScopeContext context) {
                return ScopePredicate.all();
            }
        };
    }

    private static ReadGateway gateway(AuthorizationDecisionPoint decisions) {
        return new ReadGateway(
                registry(),
                store(row()),
                List.of(allScope()),
                List.of(),
                ReadAuditSink.noop(),
                CLOCK,
                () -> UUID.randomUUID(),
                null,
                decisions);
    }

    @Test
    @DisplayName("决策点拒绝读取：403 + reason_code")
    void deniedReadIsForbiddenWithReasonCode() {
        ReadGateway gateway =
                gateway(
                        request ->
                                AuthorizationDecision.deny(
                                        "ATTRIBUTE_MISMATCH", "grade mismatch", "abac"));

        assertThatThrownBy(() -> gateway.find("employee", ID, TemporalQuery.current(), CALLER))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN);
                            assertThat(error.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
                            assertThat(error.reason()).contains("grade mismatch");
                        });
        assertThatThrownBy(
                        () ->
                                gateway.search(
                                        "employee", TemporalSearch.current("employee"), CALLER))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> gateway.history("employee", ID, CALLER))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("请求携带读权限、对象类型与资源 ID")
    void requestCarriesReadPermissionAndResource() {
        AuthorizationDecisionPoint decisions =
                request -> {
                    assertThat(request.action()).isEqualTo("hr.employee.read");
                    assertThat(request.resourceType()).isEqualTo("employee");
                    assertThat(request.resourceId()).isEqualTo(ID.toString());
                    return AuthorizationDecision.allow("rbac");
                };

        assertThat(gateway(decisions).find("employee", ID, TemporalQuery.current(), CALLER))
                .isPresent();
    }

    @Test
    @DisplayName("允许读取时字段策略仍然生效")
    void allowedReadStillAppliesFieldPolicy() {
        FieldPolicyResolver hiddenContact =
                new FieldPolicyResolver() {
                    @Override
                    public boolean supports(String objectType) {
                        return true;
                    }

                    @Override
                    public FieldPolicy resolve(
                            String objectType,
                            Caller caller,
                            cn.byteforce.seedj.core.security.ScopeContext context) {
                        return new FieldPolicy(
                                Map.of(
                                        "contact",
                                        new FieldAccess(
                                                FieldAccess.Action.HIDDEN, "field-policy")));
                    }
                };
        ReadGateway gateway =
                new ReadGateway(
                        registry(),
                        store(row()),
                        List.of(allScope()),
                        List.of(hiddenContact),
                        ReadAuditSink.noop(),
                        CLOCK,
                        () -> UUID.randomUUID(),
                        null,
                        request -> AuthorizationDecision.allow("rbac"));

        TemporalRow found =
                gateway.find("employee", ID, TemporalQuery.current(), CALLER).orElseThrow();
        assertThat(found.fields()).containsEntry("name", "张三").doesNotContainKey("contact");
    }
}
