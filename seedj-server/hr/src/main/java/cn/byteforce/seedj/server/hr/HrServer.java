package cn.byteforce.seedj.server.hr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import cn.byteforce.seedj.biz.annotation.EnableBizServer;

/**
 * HR 参考服务：验证 {@code seedj-biz-starter} + core/infra 在分布式场景下的能力。
 */
@SpringBootApplication
@EnableBizServer
@EnableMethodSecurity
public class HrServer {

    public static void main(String[] args) {
        SpringApplication.run(HrServer.class, args);
    }
}
