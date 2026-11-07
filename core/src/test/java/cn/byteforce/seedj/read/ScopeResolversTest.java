package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行范围合成：fail-closed、字段白名单校验与谓词收窄。
 */
class ScopeResolversTest {

    private static final Caller CALLER = new Caller("u-1", "alice", "default", Set.of(),
            Set.of("employee.read"), Set.of(), null, null, Map.of());

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("employee", "hr_employee")
            .field(FieldDefinition.of("org_unit_id", FieldType.TEXT))
            .field(FieldDefinition.of("status", FieldType.TEXT))
            .build();

    @Test
    @DisplayName("没有贡献者时返回 403，而不是放行")
    void noResolversIsForbidden() {
        assertThatThrownBy(() -> ScopeResolvers.resolve("employee", CALLER, ScopeContext.empty(),
                List.of(), SCHEMA))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("不支持该对象类型的贡献者被跳过")
    void unsupportedResolverIsSkipped() {
        ScopePredicate resolved = ScopeResolvers.resolve("employee", CALLER, ScopeContext.empty(),
                List.of(resolver("invoice", ScopePredicate.all()),
                        resolver("employee", ScopePredicate.eq("status", "ACTIVE"))), SCHEMA);

        assertThat(resolved).isEqualTo(ScopePredicate.eq("status", "ACTIVE"));
    }

    @Test
    @DisplayName("多个贡献者按 AND 收窄")
    void contributionsAreAnded() {
        ScopePredicate resolved = ScopeResolvers.resolve("employee", CALLER, ScopeContext.empty(),
                List.of(resolver("employee", ScopePredicate.eq("status", "ACTIVE")),
                        resolver("employee", ScopePredicate.notNull("org_unit_id"))), SCHEMA);

        assertThat(resolved).isInstanceOf(ScopePredicate.And.class);
        assertThat(((ScopePredicate.And) resolved).parts()).hasSize(2);
    }

    @Test
    @DisplayName("贡献者抛异常时返回 503")
    void resolverFailureIsUnavailable() {
        DataScopeResolver failing = new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          ScopeContext context) {
                throw new IllegalStateException("policy engine down");
            }
        };

        assertThatThrownBy(() -> ScopeResolvers.resolve("employee", CALLER, ScopeContext.empty(),
                List.of(failing), SCHEMA))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.UNAVAILABLE);
                    assertThat(error.getCause()).hasMessageContaining("policy engine down");
                });
    }

    @Test
    @DisplayName("贡献者返回 null 时失败关闭")
    void nullContributionFails() {
        assertThatThrownBy(() -> ScopeResolvers.resolve("employee", CALLER, ScopeContext.empty(),
                List.of(resolver("employee", null)), SCHEMA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data scope resolver returned null");
    }

    @Test
    @DisplayName("嵌套谓词引用未知字段时失败关闭")
    void nestedUnknownFieldFails() {
        ScopePredicate nested = ScopePredicates.and(List.of(
                ScopePredicate.eq("status", "ACTIVE"),
                ScopePredicates.or(List.of(
                        ScopePredicate.in("nope", List.of("x")),
                        ScopePredicate.not(ScopePredicate.notNull("status"))))));

        assertThatThrownBy(() -> ScopeResolvers.resolve("employee", CALLER, ScopeContext.empty(),
                List.of(resolver("employee", nested)), SCHEMA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data scope references unknown field: nope");
    }

    @Test
    @DisplayName("全部谓词形态都通过字段校验")
    void acceptsEveryPredicateShape() {
        ScopePredicate predicate = ScopePredicates.and(List.of(ScopePredicate.all(),
                ScopePredicate.eq("status", "ACTIVE"),
                ScopePredicate.in("status", List.of("ACTIVE")),
                ScopePredicate.notNull("org_unit_id"),
                ScopePredicate.not(ScopePredicate.none()),
                ScopePredicates.or(List.of(ScopePredicate.eq("status", "LEFT")))));

        assertThat(ScopeResolvers.resolve("employee", CALLER, ScopeContext.empty(),
                List.of(resolver("employee", predicate)), SCHEMA)).isEqualTo(predicate);
    }

    private static DataScopeResolver resolver(String objectType, ScopePredicate predicate) {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String type) {
                return objectType.equals(type);
            }

            @Override
            public ScopePredicate resolve(String type, Caller caller, ScopeContext context) {
                return predicate;
            }
        };
    }
}
