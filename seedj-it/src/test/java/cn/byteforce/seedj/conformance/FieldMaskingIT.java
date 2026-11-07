package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.DataScopeResolver;
import cn.byteforce.seedj.read.ReadAuditSink;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.starter.iam.abac.CompositeAuthorizationDecisionPoint;
import cn.byteforce.seedj.starter.iam.read.IamFieldPolicyResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 读路径字段 masking 负例：没有 read.masked 时敏感字段必须隐藏，writeOnly 永不出现。 */
class FieldMaskingIT {

    private static final Instant AT = Instant.parse("2026-10-15T12:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

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

    private ObjectModuleRegistry registry;

    private InMemoryTemporalStore store;

    @BeforeEach
    void setUp() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT)
                .required()
                .field("name", FieldType.TEXT)
                .field("contact", FieldType.TEXT)
                .privacy(Privacy.SENSITIVE)
                .mask(Maskers.last4())
                .field("id_number_hash", FieldType.TEXT)
                .writeOnly();
        registry = modules.freeze();
        ObjectSchema schema = registry.require("employee").schema();
        store = new InMemoryTemporalStore(() -> AT, new ObjectSchemaRegistry(List.of(schema)));
        store.append(
                AppendRequest.create(
                        "employee",
                        ID,
                        AT,
                        null,
                        ACTOR,
                        Map.of(
                                "employee_no",
                                "EMP-000001",
                                "name",
                                "张三",
                                "contact",
                                "138001234",
                                "id_number_hash",
                                "secret-hash")));
    }

    private ReadGateway gateway(boolean allowMasked) {
        IamPolicyDecisionPoint policies =
                request -> {
                    boolean contact = "contact".equals(request.resourceId());
                    if (request.action().equals("read") && !contact) {
                        return IamAuthorizationDecision.allow("allow-read");
                    }
                    if (allowMasked && contact && request.action().equals("read.masked")) {
                        return IamAuthorizationDecision.allow("allow-masked");
                    }
                    return IamAuthorizationDecision.deny("no matching allow policy");
                };
        CompositeAuthorizationDecisionPoint decisions =
                new CompositeAuthorizationDecisionPoint(
                        request -> AuthorizationDecision.allow("contract"), policies);
        return new ReadGateway(
                registry,
                store,
                List.of(allScope()),
                List.of(new IamFieldPolicyResolver(decisions, () -> AT, registry)),
                ReadAuditSink.noop(),
                () -> AT,
                () -> UUID.randomUUID(),
                null,
                decisions);
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

    @Test
    @DisplayName("没有 read.masked：SENSITIVE 字段隐藏")
    void sensitiveFieldHiddenWithoutMaskPermission() {
        TemporalRow row =
                gateway(false).find("employee", ID, TemporalQuery.current(), CALLER).orElseThrow();

        assertThat(row.fields()).containsEntry("name", "张三").doesNotContainKey("contact");
    }

    @Test
    @DisplayName("有 read.masked：SENSITIVE 字段打码")
    void sensitiveFieldMaskedWithMaskPermission() {
        TemporalRow row =
                gateway(true).find("employee", ID, TemporalQuery.current(), CALLER).orElseThrow();

        assertThat(row.fields()).containsEntry("contact", "****1234");
    }

    @Test
    @DisplayName("writeOnly 字段永远不出现")
    void writeOnlyFieldNeverAppears() {
        assertThat(
                        gateway(true)
                                .find("employee", ID, TemporalQuery.current(), CALLER)
                                .orElseThrow()
                                .fields())
                .doesNotContainKey("id_number_hash");
    }

    @Test
    @DisplayName("对象级读取被决策点拒绝时 403")
    void objectReadDeniedByDecisionPoint() {
        ReadGateway denied =
                new ReadGateway(
                        registry,
                        store,
                        List.of(allScope()),
                        List.of(),
                        ReadAuditSink.noop(),
                        () -> AT,
                        () -> UUID.randomUUID(),
                        null,
                        request ->
                                AuthorizationDecision.deny("ATTRIBUTE_MISMATCH", "grade", "abac"));

        assertThatThrownBy(() -> denied.find("employee", ID, TemporalQuery.current(), CALLER))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH"));
    }
}
