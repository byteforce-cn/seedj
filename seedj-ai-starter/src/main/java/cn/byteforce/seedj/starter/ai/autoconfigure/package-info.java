/**
 * AI 能力的自动配置入口：按「启用标记 + 角色开关」装配 guard、决策面、运行时与账本。
 *
 * <p><b>职责</b>：把 {@code seedj.ai.*} 配置与各能力包（{@code ai.guard}、{@code ai.token}、{@code ai.plane}、
 * {@code ai.runtime}）的公开类型组装成 bean，并登记到 {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}。每个配置类都以 {@code
 * @ConditionalOnBean(AiEnabledMarker.class)} 为门：模块被宿主依赖但宿主没写 {@code @EnableAIServer} 时，这里一个
 * bean 都不产。
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 的 AI 契约、本模块的能力包与 {@code seedj-biz-starter} 的命令目录（用于
 * 交叉校验）；只能被 Spring Boot 的自动配置清单引用，禁止被业务包反向依赖，也禁止在这里写业务逻辑。
 */
package cn.byteforce.seedj.starter.ai.autoconfigure;
