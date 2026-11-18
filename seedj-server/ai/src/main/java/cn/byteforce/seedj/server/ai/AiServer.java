package cn.byteforce.seedj.server.ai;

import cn.byteforce.seedj.starter.ai.annotation.EnableAIServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 决策面参考服务的可运行宿主。
 *
 * <p>决策面端点由 {@code seedj-ai-starter} 在 {@code seedj.ai.plane.enabled=true} 时装配；本宿主只负责开启。
 *
 * <p>角色是<b>决策面</b>，不是执行侧：{@code seedj.ai.guard.enabled=false}（见 {@code application.yml}），同一份
 * starter 在业务服务里才当守卫用。
 */
@SpringBootApplication
@EnableAIServer
public class AiServer {

    public static void main(String[] args) {
        SpringApplication.run(AiServer.class, args);
    }
}
