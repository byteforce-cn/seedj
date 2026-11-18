package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.starter.ai.annotation.EnableAIServer;
import cn.byteforce.seedj.starter.annotation.EnableBizServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * T0 pilot 的宿主：<b>一个进程同时当决策面、执行侧与 agent 运行时</b>（spec §5.1 的两条路径都在本进程内闭环）。
 *
 * <p>三个角色各有开关（{@code seedj.ai.plane/guard/runtime}，见 {@code application-pilot.yml}）：
 * {@code @EnableAIServer} 只是把它们一次装好；{@code @EnableBizServer} 提供命令运行时、对象存储、审计链与安全链接入，
 * 也就是 {@link Ticket} 这类业务对象真正落库的地方。
 *
 * <p>角色同进程是 T0 的部署形态，不是目标形态：好处是端到端用例能用真实 HTTP 打自己（guard 用同一把 {@code kid} 公钥
 * 验决策面签出的 token），坏处是「哪一侧生效」需要在配置里读清楚——所以三个开关都显式写出来，不靠默认值。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootApplication
@EnableBizServer
@EnableAIServer
public class PilotApplication {

    /**
     * 启动 pilot 宿主。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PilotApplication.class, args);
    }
}
