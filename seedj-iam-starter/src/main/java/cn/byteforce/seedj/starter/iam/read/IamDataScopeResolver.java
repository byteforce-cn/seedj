package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.read.DataScopeResolver;

import java.util.Objects;

/**
 * 把 IAM 策略作为追加的行范围贡献者；没有 ALLOW 时返回 {@code None}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamDataScopeResolver implements DataScopeResolver {

    private final PolicyScopeCompiler compiler;

    private final ObjectModuleRegistry modules;

    /**
     * 构造解析器。
     *
     * @param compiler 策略编译器
     * @param modules  模块注册表
     */
    public IamDataScopeResolver(PolicyScopeCompiler compiler, ObjectModuleRegistry modules) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    @Override
    public boolean supports(String objectType) {
        return modules.find(objectType).isPresent();
    }

    @Override
    public ScopePredicate resolve(String objectType, Caller caller, ScopeContext context) {
        return compiler.compile(objectType, caller, context);
    }
}
