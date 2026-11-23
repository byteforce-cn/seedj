package cn.byteforce.seedj.infra.coord;

import org.springframework.core.env.Environment;

/** 命名空间解析：显式配置优先，否则 {@code seedj:<spring.application.name>}。 */
final class CoordNamespaces {

    private CoordNamespaces() {}

    static String of(CoordProperties properties, Environment environment) {
        return properties.resolveNamespace(environment);
    }
}
