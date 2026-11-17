package cn.byteforce.seedj.starter.im.web;

import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.starter.im.ImCommandAutoConfiguration;
import cn.byteforce.seedj.starter.im.ImConversationService;
import cn.byteforce.seedj.starter.im.ImMessagingService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 管理端点装配：只在有 Web 栈与已认证调用方入口的宿主里注册。
 *
 * <p>端点本身不做授权，租户与成员判定在 {@link ImConversationService}；{@code /im/**} 由过滤器链 统一要求已认证。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = ImCommandAutoConfiguration.class)
@ConditionalOnClass(RestController.class)
@ConditionalOnBean({CallerProvider.class, ImConversationService.class, ImMessagingService.class})
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(
        prefix = "seedj.im.http",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ImWebAutoConfiguration {

    /**
     * 管理端点。
     *
     * @param callers 调用方入口
     * @param conversations 会话服务
     * @param messaging 消息服务
     * @return 端点
     */
    @Bean
    @ConditionalOnMissingBean
    ImAdminController imAdminController(
            CallerProvider callers,
            ImConversationService conversations,
            ImMessagingService messaging) {
        return new ImAdminController(callers, conversations, messaging);
    }
}
