package cn.byteforce.seedj.starter.coord.registry;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * 把 {@code WebServerInitializedEvent} 的监听隔离到独立类：非 web 应用
 * （classpath 缺 {@code spring-boot-web-server}）时本类不会被加载，
 * 自动配置中的条件配置整体跳过。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordWebServerPortListener implements ApplicationListener<WebServerInitializedEvent> {

    private final CoordAutoRegistration registration;

    /**
     * @param registration 自动注册组件
     */
    public CoordWebServerPortListener(CoordAutoRegistration registration) {
        this.registration = registration;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        registration.onPortAvailable(event.getWebServer().getPort());
    }
}
