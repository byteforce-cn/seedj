package cn.byteforce.seedj.starter.data.mybatis.handler;

import cn.byteforce.seedj.starter.data.mybatis.scan.ScanState;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.type.TypeHandler;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CodeEnumTypeHandlerRegistrar} 扫描与注册契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CodeEnumTypeHandlerRegistrarTest {

    @Test
    void scansAndRegistersCodeEnumHandlers() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        CodeEnumTypeHandlerRegistrar registrar = new CodeEnumTypeHandlerRegistrar(
                Set.of("cn.byteforce.seedj.starter.data.mybatis.scan"));

        registrar.customize(configuration);

        TypeHandler<?> handler = configuration.getTypeHandlerRegistry().getTypeHandler(ScanState.class);
        assertThat(handler).isInstanceOf(CodeEnumTypeHandler.class);
    }

    @Test
    void emptyScanDoesNotFail() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        CodeEnumTypeHandlerRegistrar registrar = new CodeEnumTypeHandlerRegistrar(
                Set.of("cn.byteforce.seedj.nonexistent.package"));

        registrar.customize(configuration);

        // MyBatis 对枚举有默认的 CompositeEnumTypeHandler；这里验证未注册「我们的」handler
        assertThat(configuration.getTypeHandlerRegistry().getTypeHandler(ScanState.class))
                .isNotInstanceOf(CodeEnumTypeHandler.class);
    }
}
