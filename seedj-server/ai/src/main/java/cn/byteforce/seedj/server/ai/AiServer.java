package cn.byteforce.seedj.server.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 决策面参考服务的可运行宿主。
 *
 * <p>决策面端点由 {@code seedj-ai-starter} 在 {@code seedj.ai.plane.enabled=true} 时装配；本宿主只负责开启。
 */
@SpringBootApplication
public class AiServer {

    public static void main(String[] args) {
        SpringApplication.run(AiServer.class, args);
    }
}
