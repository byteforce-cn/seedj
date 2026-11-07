package cn.byteforce.seedj.server.iam;

import cn.byteforce.seedj.starter.iam.annotation.EnableIamServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一认证/授权服务（OIDC Provider）的可运行宿主。
 *
 * <p>用户存储、客户端注册等运行期数据由部署方提供；测试夹具在 {@code seedj-it} 中以额外配置源注入。
 */
@SpringBootApplication
@EnableIamServer
public class IamServer {

    public static void main(String[] args) {
        SpringApplication.run(IamServer.class, args);
    }
}
