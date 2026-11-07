package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 行范围合成：读网关与写范围提供者共用同一套 fail-closed 语义。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ScopeResolvers {

    private ScopeResolvers() {}

    /**
     * 解析并合成所有支持的贡献者。
     *
     * @param objectType 对象类型
     * @param caller 调用方
     * @param context 请求事实
     * @param resolvers 贡献者（按调用顺序）
     * @param schema 对象 schema
     * @return AND 收窄后的谓词
     */
    public static ScopePredicate resolve(
            String objectType,
            Caller caller,
            ScopeContext context,
            List<DataScopeResolver> resolvers,
            ObjectSchema schema) {
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(resolvers, "resolvers");
        Objects.requireNonNull(schema, "schema");
        List<ScopePredicate> parts = new ArrayList<>();
        for (DataScopeResolver resolver : resolvers) {
            if (!resolver.supports(objectType)) {
                continue;
            }
            ScopePredicate predicate;
            try {
                predicate = resolver.resolve(objectType, caller, context);
            } catch (RuntimeException error) {
                ContractException unavailable =
                        new ContractException(
                                ContractErrorCode.UNAVAILABLE,
                                "data scope resolution failed for " + objectType);
                unavailable.initCause(error);
                throw unavailable;
            }
            if (predicate == null) {
                throw new IllegalStateException(
                        "data scope resolver returned null for " + objectType);
            }
            requireKnownFields(predicate, schema);
            parts.add(predicate);
        }
        if (parts.isEmpty()) {
            throw new ContractException(
                    ContractErrorCode.FORBIDDEN, "no data scope resolver for " + objectType);
        }
        return ScopePredicates.and(parts);
    }

    private static void requireKnownFields(ScopePredicate predicate, ObjectSchema schema) {
        switch (predicate) {
            case ScopePredicate.All ignored -> {}
            case ScopePredicate.None ignored -> {}
            case ScopePredicate.Eq eq -> field(schema, eq.field());
            case ScopePredicate.In in -> field(schema, in.field());
            case ScopePredicate.NotNull notNull -> field(schema, notNull.field());
            case ScopePredicate.And and ->
                    and.parts().forEach(part -> requireKnownFields(part, schema));
            case ScopePredicate.Or or ->
                    or.parts().forEach(part -> requireKnownFields(part, schema));
            case ScopePredicate.Not not -> requireKnownFields(not.part(), schema);
        }
    }

    private static void field(ObjectSchema schema, String field) {
        if (schema.field(field).isEmpty()) {
            throw new IllegalStateException("data scope references unknown field: " + field);
        }
    }
}
