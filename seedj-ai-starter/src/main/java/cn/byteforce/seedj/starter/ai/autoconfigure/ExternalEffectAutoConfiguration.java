package cn.byteforce.seedj.starter.ai.autoconfigure;

import cn.byteforce.seedj.ai.spi.ExternalEffectStore;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.effect.ExternalEffectService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 外部效果装配：有存储端口才装配；审计可选。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean({AiEnabledMarker.class, ExternalEffectStore.class})
public class ExternalEffectAutoConfiguration {

    /**
     * 两段协议服务。
     *
     * @param store 效果存储
     * @param audit 审计追加端口；可为空
     * @param clock 取时入口
     * @return 服务
     */
    @Bean
    @ConditionalOnMissingBean
    public ExternalEffectService externalEffectService(
            ExternalEffectStore store,
            ObjectProvider<AuditAppender<String>> audit,
            ObjectProvider<Clock> clocks) {
        return new ExternalEffectService(
                store, audit.getIfAvailable(), clocks.getIfAvailable(Clocks::system));
    }
}
