package cn.byteforce.seedj.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 核心安全模型：请求事实类型校验、主体映射、安全审计事件与派生字段注册表。
 */
class SecurityModelTest {

    @Test
    @DisplayName("ScopeKeyType 按声明类型校验并给出可读错误")
    void scopeKeyTypeValidates() {
        assertThat(ScopeKeyType.STRING.validate("org", "D01")).isEqualTo("D01");
        assertThat(ScopeKeyType.NUMBER.validate("n", 12)).isEqualTo(12);
        assertThat(ScopeKeyType.BOOLEAN.validate("b", Boolean.TRUE)).isEqualTo(Boolean.TRUE);
        assertThat(ScopeKeyType.DATE.validate("d", LocalDate.of(2026, 1, 5)))
                .isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ScopeKeyType.LIST_STRING.validate("l", List.of("a")))
                .isEqualTo(List.of("a"));
        UUID uuid = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
        assertThat(ScopeKeyType.LIST_UUID.validate("u", List.of(uuid)))
                .isEqualTo(List.of(uuid));

        assertThatThrownBy(() -> ScopeKeyType.STRING.validate("org", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be String");
        assertThatThrownBy(() -> ScopeKeyType.STRING.validate("org", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        assertThatThrownBy(() -> ScopeKeyType.LIST_STRING.validate("l", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a list");
        assertThatThrownBy(() -> ScopeKeyType.LIST_STRING.validate("l", List.of(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain String values");
    }

    @Test
    @DisplayName("ScopeContext 拒绝空 key 与 null 值，并按声明类型读取")
    void scopeContextValidatesFacts() {
        ScopeKey<String> org = new ScopeKey<>("org.unit", ScopeKeyType.STRING);
        ScopeContext context = ScopeContext.of(Map.of("org.unit", "D01"));

        assertThat(context.get(org)).contains("D01");
        assertThat(context.get(new ScopeKey<>("org.other", ScopeKeyType.STRING))).isEmpty();
        assertThat(context.facts()).containsEntry("org.unit", "D01");

        assertThatThrownBy(() -> ScopeContext.of(Map.of(" ", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key must not be blank");
        assertThatCode(() -> ScopeContext.of(Map.of(org.name(), "x")))
                .doesNotThrowAnyException();
        Map<String, Object> withNullValue = new java.util.HashMap<>();
        withNullValue.put("org.unit", null);
        assertThatThrownBy(() -> ScopeContext.of(withNullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org.unit must not be null");
    }

    @Test
    @DisplayName("ScopeKeyCatalog 冻结声明并校验类型一致")
    void scopeKeyCatalogFreezesDeclarations() {
        ScopeKeyCatalog catalog = ScopeKeyCatalog.of(List.of(
                new ScopeKey<>("org.unit", ScopeKeyType.STRING)));

        assertThat(catalog.require("org.unit").type()).isEqualTo(ScopeKeyType.STRING);
        assertThat(catalog.keys()).containsEntry("org.unit", ScopeKeyType.STRING);
        assertThat(catalog.all()).hasSize(1);
        catalog.require(new ScopeKey<>("org.unit", ScopeKeyType.STRING));

        assertThatThrownBy(() -> catalog.require("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown scope key");
        assertThatThrownBy(() -> catalog.require(new ScopeKey<>("org.unit",
                ScopeKeyType.NUMBER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is declared as STRING");
        assertThatThrownBy(() -> ScopeKeyCatalog.of(List.of(
                new ScopeKey<>("dup", ScopeKeyType.STRING),
                new ScopeKey<>("dup", ScopeKeyType.NUMBER))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate scope key with different type");
    }

    @Test
    @DisplayName("CallerIdCodec.uuid 要求主体标识本身是 UUID")
    void callerIdCodecUuid() {
        UUID id = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
        Caller valid = caller(id.toString());

        assertThat(CallerIdCodec.uuid().toActor(valid)).isEqualTo(id);
        assertThatThrownBy(() -> CallerIdCodec.uuid().toActor(caller("u-1")))
                .isInstanceOfSatisfying(cn.byteforce.seedj.core.exception.ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                cn.byteforce.seedj.core.exception.ContractErrorCode.UNAUTHENTICATED))
                .hasMessageContaining("caller id is not a UUID");
    }

    @Test
    @DisplayName("CallerIdCodec.nameUuid 把任意主体稳定映射为 UUID")
    void callerIdCodecNameUuid() {
        Caller caller = caller("alice@example.com");

        UUID first = CallerIdCodec.nameUuid().toActor(caller);
        UUID second = CallerIdCodec.nameUuid().toActor(caller("alice@example.com"));

        assertThat(first).isEqualTo(second);
        assertThat(first.version()).isEqualTo(3);
        assertThat(CallerIdCodec.nameUuid().toActor(caller("bob@example.com")))
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("主体标识为空时编解码失败")
    void callerIdCodecRequiresId() {
        assertThatThrownBy(() -> CallerIdCodec.uuid().toActor(caller(" ")))
                .isInstanceOf(cn.byteforce.seedj.core.exception.ContractException.class)
                .hasMessageContaining("caller id is required");
        assertThatThrownBy(() -> CallerIdCodec.nameUuid().toActor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Caller 归一化空白与缺失的声明字段")
    void callerNormalisesAbsentValues() {
        Caller caller = new Caller(null, null, " ", null, null, null, null, null, null);

        assertThat(caller.id()).isEmpty();
        assertThat(caller.username()).isEmpty();
        assertThat(caller.tenantId()).isEqualTo(Caller.DEFAULT_TENANT);
        assertThat(caller.roles()).isEmpty();
        assertThat(caller.permissions()).isEmpty();
        assertThat(caller.amr()).containsExactly(Caller.DEFAULT_AMR);
        assertThat(caller.acr()).isEqualTo(Caller.DEFAULT_ACR);
        assertThat(caller.attributes()).isEmpty();
        assertThat(caller.idIfPresent()).isEmpty();
        assertThat(caller.hasGroup("g")).isFalse();
    }

    @Test
    @DisplayName("Caller 的角色、权限与组查询对 null 安全")
    void callerPredicates() {
        Caller caller = new Caller("u-1", "alice", "acme", Set.of("admin"),
                Set.of("employee.read"), Set.of("hr"), Set.of("pwd"), null, Map.of("k", "v"));

        assertThat(caller.hasRole("admin")).isTrue();
        assertThat(caller.hasRole(null)).isFalse();
        assertThat(caller.hasPermission("employee.read")).isTrue();
        assertThat(caller.hasPermission(null)).isFalse();
        assertThat(caller.hasGroup("hr")).isTrue();
        assertThat(caller.hasGroup(null)).isFalse();
        assertThat(caller.idIfPresent()).contains("u-1");
        assertThat(caller.tenantId()).isEqualTo("acme");
    }

    @Test
    @DisplayName("SecurityAuditEvent 归一化缺省字段并冻结 details")
    void securityAuditEventNormalises() {
        SecurityAuditEvent event = new SecurityAuditEvent("evt-1", " ", null, null, null, null,
                null, null, null, null);

        assertThat(event.id()).isEqualTo("evt-1");
        assertThat(event.tenantId()).isEqualTo(Caller.DEFAULT_TENANT);
        assertThat(event.actorId()).isEmpty();
        assertThat(event.action()).isEmpty();
        assertThat(event.resourceType()).isEmpty();
        assertThat(event.resourceId()).isEmpty();
        assertThat(event.outcome()).isEqualTo("UNKNOWN");
        assertThat(event.reason()).isEmpty();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.details()).isEmpty();
    }

    @Test
    @DisplayName("SecurityAuditEvent 工厂方法以调用方为主体")
    void securityAuditEventFactories() {
        SecurityAuditEvent minimal = SecurityAuditEvent.of("evt-1", "u-1", "order.delete",
                "order", "o-1", SecurityAuditEvent.OUTCOME_DENIED, "no permission");
        SecurityAuditEvent fromCaller = SecurityAuditEvent.of("evt-2", caller("u-1"),
                "order.delete", "order", "o-1", SecurityAuditEvent.OUTCOME_FAILURE, "down");
        SecurityAuditEvent anonymous = SecurityAuditEvent.of("evt-3", (Caller) null,
                "order.delete", "order", "o-1", SecurityAuditEvent.OUTCOME_SUCCESS, null);

        assertThat(minimal.tenantId()).isEqualTo(Caller.DEFAULT_TENANT);
        assertThat(minimal.actorId()).isEqualTo("u-1");
        assertThat(minimal.outcome()).isEqualTo(SecurityAuditEvent.OUTCOME_DENIED);
        assertThat(fromCaller.actorId()).isEqualTo("u-1");
        assertThat(fromCaller.outcome()).isEqualTo(SecurityAuditEvent.OUTCOME_FAILURE);
        assertThat(anonymous.actorId()).isEmpty();
        assertThat(anonymous.tenantId()).isEqualTo(Caller.DEFAULT_TENANT);
        assertThatThrownBy(() -> new SecurityAuditEvent(" ", null, null, null, null, null, null,
                null, Instant.now(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must not be blank");
    }

    @Test
    @DisplayName("SecurityErrorCode 给出分类、可重试性与 HTTP 状态")
    void securityErrorCode() {
        assertThat(cn.byteforce.seedj.core.exception.ContractErrorCode.UNAUTHENTICATED.httpStatus()).isEqualTo(401);
        assertThat(SecurityErrorCode.ACCESS_DENIED.httpStatus()).isEqualTo(403);
        assertThat(SecurityErrorCode.ACCESS_DENIED.category()).isNotNull();
        assertThat(SecurityErrorCode.AUTHENTICATION_FAILED.defaultRetryable()).isFalse();
        assertThat(SecurityErrorCode.TOKEN_EXPIRED.httpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("TokenClaims 归一化缺失声明")
    void tokenClaimsNormalise() {
        TokenClaims claims = new TokenClaims(null, null, null, null, null, null);

        assertThat(claims.subject()).isEmpty();
        assertThat(claims.type()).isEmpty();
        assertThat(claims.issuer()).isEmpty();
        assertThat(claims.audience()).isEmpty();
        assertThat(claims.attributes()).isEmpty();
    }

    @Test
    @DisplayName("DerivedFieldRegistry 按逻辑名索引派生字段，未注册即失败")
    void derivedFieldRegistry() {
        DerivedField field = new DerivedField("seedj:hr:employee:id_number_hash:v1",
                new DerivedFieldKeyRing("key-1"));
        DerivedFieldRegistry registry = new DerivedFieldRegistry(
                Map.of("id_number_hash", field));

        assertThat(registry.require("id_number_hash")).isSameAs(field);
        assertThat(registry.names()).containsExactly("id_number_hash");
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown derived field: missing")
                .hasMessageContaining("id_number_hash");
        assertThatThrownBy(() -> new DerivedFieldRegistry(Map.of(" ", field)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived field name is required");
        assertThatThrownBy(() -> new DerivedFieldRegistry(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("DerivedField 校验 scope 与密钥环，空原始值失败")
    void derivedFieldGuards() {
        assertThatThrownBy(() -> new DerivedField("Bad Scope",
                new DerivedFieldKeyRing("key-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal derived-field scope");
        assertThatThrownBy(() -> new DerivedField("seedj:hr:employee:x:v1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keys are required");

        DerivedField field = new DerivedField("seedj:hr:employee:x:v1",
                new DerivedFieldKeyRing("key-1"));

        assertThat(field.matches("raw", null)).isFalse();
        assertThatThrownBy(() -> field.derive(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("raw value must not be blank");
    }

    private static Caller caller(String id) {
        return new Caller(id, "alice", "default", Set.of(), Set.of(), Set.of(), Set.of("pwd"),
                Caller.DEFAULT_ACR, Map.of());
    }
}
