package cn.byteforce.seedj.server.im;

import cn.byteforce.seedj.starter.im.EnableImServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** IM 参考服务：TCP/WebSocket 长连接宿主，身份只来自 IAM 签发的 JWT。 */
@SpringBootApplication
@EnableImServer
public class ImServer {

    public static void main(String[] args) {
        SpringApplication.run(ImServer.class, args);
    }
}
