package cn.byteforce.seedj.server.im;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import cn.byteforce.seedj.biz.annotation.EnableBizServer;

/**
 * IM 参考服务：验证长连接、扇出、顺序、幂等等非传统业务对 core 能力的要求。
 */
@SpringBootApplication
@EnableBizServer
@EnableMethodSecurity
public class ImServer {

    public static void main(String[] args) {
        SpringApplication.run(ImServer.class, args);
    }
}
