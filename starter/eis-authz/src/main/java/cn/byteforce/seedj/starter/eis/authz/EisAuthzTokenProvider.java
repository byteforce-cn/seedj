package cn.byteforce.seedj.starter.eis.authz;

import java.util.Optional;

/**
 * EIS 授权服务调用凭据 SPI：为消费方（{@link EisAuthzClient}）提供
 * {@code Authorization: Bearer <token>} 令牌。
 *
 * <p>默认实现为 {@link EisAuthzClientCredentialsTokenProvider}（OAuth2
 * client_credentials）；使用方可注册自己的实现 Bean 覆盖（
 * {@code @ConditionalOnMissingBean}），以复用既有服务身份/令牌管理体系。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface EisAuthzTokenProvider {

    /**
     * 获取当前可用的访问令牌。
     *
     * @return 访问令牌；无可用令牌为空（调用方不携带认证头）
     */
    Optional<String> accessToken();
}
