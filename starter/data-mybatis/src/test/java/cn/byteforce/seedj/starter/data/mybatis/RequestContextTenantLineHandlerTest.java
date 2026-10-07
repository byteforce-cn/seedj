package cn.byteforce.seedj.starter.data.mybatis;

import cn.byteforce.seedj.core.context.RequestContext;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestContextTenantLineHandler} 契约测试：fail-closed 语义与忽略表。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class RequestContextTenantLineHandlerTest {

    private final RequestContextTenantLineHandler handler =
            new RequestContextTenantLineHandler("tenant_id", Set.of("sys_dict"));

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void returnsTenantIdFromContext() {
        RequestContext.setTenantId(9L);

        assertThat(handler.getTenantId()).isInstanceOf(LongValue.class);
        assertThat(((LongValue) handler.getTenantId()).getValue()).isEqualTo(9L);
        assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
    }

    @Test
    void failsClosedWhenTenantMissing() {
        assertThatThrownBy(handler::getTenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户");
    }

    @Test
    void ignoreTablesIsCaseInsensitive() {
        assertThat(handler.ignoreTable("sys_dict")).isTrue();
        assertThat(handler.ignoreTable("SYS_DICT")).isTrue();
        assertThat(handler.ignoreTable("orders")).isFalse();
        assertThat(handler.ignoreTable(null)).isFalse();
    }
}
