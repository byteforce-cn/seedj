package cn.byteforce.seedj.module;

/**
 * 实体声明入口：一个实体一个 configurer，框架从声明派生 schema、命令、查询与读端点。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface ObjectModuleConfigurer {

    /**
     * 声明对象。
     *
     * @param modules 模块 DSL
     */
    void configure(ObjectModules modules);
}
