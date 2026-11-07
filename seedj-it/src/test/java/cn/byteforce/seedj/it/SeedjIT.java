package cn.byteforce.seedj.it;

import cn.byteforce.seedj.it.extension.ClusterExtension;
import cn.byteforce.seedj.it.extension.ClusterService;
import cn.byteforce.seedj.it.extension.EnableClusterServices;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

@ExtendWith(ClusterExtension.class)
@EnableClusterServices({"iam", "im", "hr"})
public class SeedjIT {

    @Test
    @DisplayName("验证 IAM 服务健康检查")
    void iamShouldBeResponsive(@ClusterService("iam") RestTestClient iamClient) {
        // 1. 先获取响应结果，不要直接断言
        var result = iamClient.get().uri("/actuator/health").exchange().returnResult(String.class);

        // 2. 打印状态码和响应体（JSON）
        System.out.println("=== IAM Health Response ===");
        System.out.println("Status: " + result.getStatus());
        System.out.println(
                "Body: " + new String(result.getResponseBodyContent(), StandardCharsets.UTF_8));
        System.out.println("===========================");

        // 3. 手动断言状态是否为 200
        Assertions.assertEquals(HttpStatus.OK, result.getStatus(), "IAM 健康检查失败，请查看上面的打印日志");
    }

    @Test
    @DisplayName("验证 Im 服务健康检查")
    void imShouldBeResponsive(@ClusterService("im") RestTestClient imClient) {
        // 1. 先获取响应结果，不要直接断言
        var result = imClient.get().uri("/actuator/health").exchange().returnResult(String.class);

        // 2. 打印状态码和响应体（JSON）
        System.out.println("=== IM Health Response ===");
        System.out.println("Status: " + result.getStatus());
        System.out.println(
                "Body: " + new String(result.getResponseBodyContent(), StandardCharsets.UTF_8));
        System.out.println("===========================");

        // 3. 手动断言状态是否为 200
        Assertions.assertEquals(HttpStatus.OK, result.getStatus(), "IM 健康检查失败，请查看上面的打印日志");
    }

    @Test
    @DisplayName("验证 Hr 服务健康检查")
    void hrShouldBeResponsive(@ClusterService("hr") RestTestClient hrClient) {
        // 1. 先获取响应结果，不要直接断言
        var result = hrClient.get().uri("/actuator/health").exchange().returnResult(String.class);

        // 2. 打印状态码和响应体（JSON）
        System.out.println("=== HR Health Response ===");
        System.out.println("Status: " + result.getStatus());
        System.out.println(
                "Body: " + new String(result.getResponseBodyContent(), StandardCharsets.UTF_8));
        System.out.println("===========================");

        // 3. 手动断言状态是否为 200
        Assertions.assertEquals(HttpStatus.OK, result.getStatus(), "HR 健康检查失败，请查看上面的打印日志");
    }
}
