package cn.byteforce.seedj.starter.ai.runtime.llm;

import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.runtime.LlmGateway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * LLM 客户端装配：{@code seedj.ai.llm.enabled=true} 时提供一个 OpenAI 兼容的 {@link LlmGateway}。
 *
 * <p>门控是两层的：{@link AiEnabledMarker}（应用写了 {@code @EnableAIServer}）与 {@code
 * seedj.ai.llm.enabled}（<b>默认 false</b>）。宿主自己提供 {@link LlmGateway} bean 时本配置让位， 可以用它接非 OpenAI
 * 协议的服务。
 *
 * <p>与 {@code AiRuntimeAutoConfiguration} 分开登记（两个类互不引用）：runtime 装配循环并消费 {@link
 * LlmGateway}，本配置生产它。这样 {@code ai.runtime} 与 {@code ai.runtime.llm} 之间没有包环。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(AiEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.ai.llm", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({AiProperties.class, LlmProperties.class})
public class LlmAutoConfiguration {

    /**
     * OpenAI 兼容客户端。
     *
     * <p>克隆宿主的 {@link RestClient.Builder} 再设超时，不改宿主的构造器。
     *
     * @param llm LLM 配置
     * @param ai AI 配置：工具白名单与任务契约
     * @param restClientBuilder 宿主/Boot 提供的 HTTP 客户端构造器
     * @return LLM 端口
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmGateway llmGateway(
            LlmProperties llm, AiProperties ai, RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) llm.timeout().toMillis();
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return new OpenAiCompatibleLlmGateway(
                restClientBuilder.clone().requestFactory(factory), llm, ai);
    }
}
