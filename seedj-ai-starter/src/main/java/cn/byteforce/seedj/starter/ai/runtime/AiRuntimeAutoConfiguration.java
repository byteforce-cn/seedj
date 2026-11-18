package cn.byteforce.seedj.starter.ai.runtime;

import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * agent 循环装配：LLM 端口 → 决策面客户端 → 工具客户端 → {@link BoundedLoop}。
 *
 * <p>门控是两层的：{@link AiEnabledMarker}（应用写了 {@code @EnableAIServer}）与 {@code
 * seedj.ai.runtime.enabled}（<b>默认 false</b>——决策面/执行侧不需要 agent 循环）。
 *
 * <p><b>LLM 端口必须由宿主提供</b>：{@link BoundedLoop} 只在上下文里有 {@link LlmGateway} 时装配，本模块不造默认
 * 实现——「哪个模型、什么提示词」是宿主的部署事实，starter 猜不出来。缺端口时决策面与工具客户端照常装配（宿主可以只用 它们），只有循环没有。
 *
 * <p><b>model 是部署事实，不是运行时输入</b>：{@code seedj.ai.runtime.model.name} 与 {@code
 * seedj.ai.runtime.model.version} 在 {@code runtime.enabled=true} 时必填，缺失即启动失败并点名配置键。它们进 决策请求的 {@code
 * model} 字段，也就是账本里的「哪一版模型/提示词做了这个决定」：换模型或换提示词版本必须改配置， 不能由 agent 或请求方自报。
 *
 * <p>两个 HTTP 客户端都基于 Boot 提供的 {@link RestClient.Builder}（克隆一份再设超时，不改宿主的构造器），地址与超时 来自 {@code
 * seedj.ai.runtime.decision-base-url} / {@code command-base-url} / {@code request-timeout}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(AiEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.ai.runtime", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiProperties.class)
public class AiRuntimeAutoConfiguration {

    /**
     * 决策面客户端：每一步把提案交给 {@code POST /v1/decisions}。
     *
     * @param properties AI 配置
     * @param restClientBuilder 宿主/Boot 提供的 HTTP 客户端构造器
     * @return 决策端口
     * @throws IllegalStateException {@code seedj.ai.runtime.model.name} 或 {@code .version} 未配置
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionClient decisionClient(
            AiProperties properties, RestClient.Builder restClientBuilder) {
        AiProperties.Runtime runtime = properties.runtime();
        return new HttpDecisionClient(
                clientBuilder(restClientBuilder, runtime.requestTimeout()),
                runtime.decisionBaseUrl(),
                requiredModel(runtime.model()));
    }

    /**
     * 工具客户端：执行走 {@code POST /commands/{type}}，只读走 {@code GET /queries/{type}}。
     *
     * @param properties AI 配置
     * @param restClientBuilder 宿主/Boot 提供的 HTTP 客户端构造器
     * @return 工具端口
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolInvoker toolInvoker(AiProperties properties, RestClient.Builder restClientBuilder) {
        AiProperties.Runtime runtime = properties.runtime();
        return new HttpToolInvoker(
                clientBuilder(restClientBuilder, runtime.requestTimeout()),
                runtime.commandBaseUrl());
    }

    /**
     * 有界 agent 循环：只在宿主提供 LLM 端口时装配。
     *
     * @param gateway 宿主提供的 LLM 端口
     * @param decisions 决策端口
     * @param tools 工具端口
     * @param properties AI 配置：步数上限与 token 受众
     * @return 循环
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(LlmGateway.class)
    public BoundedLoop boundedLoop(
            LlmGateway gateway,
            DecisionClient decisions,
            ToolInvoker tools,
            AiProperties properties) {
        return new BoundedLoop(
                gateway,
                decisions,
                tools,
                properties.runtime().maxSteps(),
                properties.decision().audience());
    }

    private static RestClient.Builder clientBuilder(
            RestClient.Builder restClientBuilder, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) timeout.toMillis();
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return restClientBuilder.clone().requestFactory(factory);
    }

    private static AiProperties.Runtime.Model requiredModel(AiProperties.Runtime.Model model) {
        requireText(model.name(), "seedj.ai.runtime.model.name");
        requireText(model.version(), "seedj.ai.runtime.model.version");
        return model;
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 未配置：model 是部署事实（换模型/提示词版本必须改配置），不由 agent 自报");
        }
    }
}
