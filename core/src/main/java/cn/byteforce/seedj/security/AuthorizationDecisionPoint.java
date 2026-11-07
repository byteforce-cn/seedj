package cn.byteforce.seedj.security;

/**
 * 唯一授权决策点 SPI：命令、读路径与字段策略都从这里取决定。
 *
 * <p>实现方负责 RBAC/SoD/ABAC 的组合；调用方不得再直接读 JWT 权限声明做判断。 同一个实例必须能同时回答命令权限（{@code action =
 * permission}）与字段读取 （{@code action = read/read.masked}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface AuthorizationDecisionPoint {

    /**
     * 做一次授权决策。
     *
     * @param request 请求
     * @return 决策；不得返回 {@code null}
     */
    AuthorizationDecision decide(AuthorizationRequest request);
}
