package cn.byteforce.seedj.starter.eis.domain.internal;

import cn.byteforce.seedj.starter.eis.domain.AccountEvent;
import cn.byteforce.seedj.starter.eis.domain.AggregateRoot;
import cn.byteforce.seedj.starter.eis.domain.EventApplier;
import cn.byteforce.seedj.starter.eis.domain.TestAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分派表：继承、父类型兜底、sealed 穷尽与非法写法。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EventDispatchTest {

    @Test
    @DisplayName("精确匹配：每个事件找到自己的 apply")
    void exactMatch() {
        Method method = EventDispatch.methodFor(TestAccount.class, AccountEvent.Opened.class).orElseThrow();

        assertThat(method.getDeclaringClass()).isEqualTo(TestAccount.class);
        assertThat(method.getParameterTypes()).containsExactly(AccountEvent.Opened.class);
    }

    @Test
    @DisplayName("父类型兜底：apply(事件族) 能处理所有子类型")
    void parentHandlerCoversSubtypes() {
        Method method = EventDispatch.methodFor(FamilyHandler.class, AccountEvent.Frozen.class).orElseThrow();

        assertThat(method.getParameterTypes()).containsExactly(AccountEvent.class);
    }

    @Test
    @DisplayName("子类覆盖优先")
    void subclassOverrideWins() {
        Method method = EventDispatch.methodFor(ChildHandler.class, AccountEvent.Opened.class).orElseThrow();

        assertThat(method.getDeclaringClass()).isEqualTo(ChildHandler.class);
    }

    @Test
    @DisplayName("静态 apply 与抽象 apply 不进分派表")
    void staticAndAbstractIgnored() {
        assertThat(EventDispatch.table(AbstractHandler.class)).isEmpty();
    }

    @Test
    @DisplayName("参数不是 DomainEvent 的 apply 不进分派表（启动期会另行报错）")
    void nonEventParameterIgnored() {
        assertThat(EventDispatch.table(WrongParameterHandler.class)).isEmpty();
        assertThat(EventDispatch.applyMethods(WrongParameterHandler.class)).hasSize(1);
    }

    @Test
    @DisplayName("事件族解析：直接实现 / 子类继承 / 未声明")
    void eventFamilyResolution() {
        assertThat(EventDispatch.eventFamily(TestAccount.class)).contains(AccountEvent.class);
        assertThat(EventDispatch.eventFamily(ChildHandler.class)).contains(AccountEvent.class);
        assertThat(EventDispatch.eventFamily(PlainRoot.class)).isEmpty();
    }

    @Test
    @DisplayName("sealed 穷尽校验：缺失项被列出，覆盖项通过")
    void missingHandlers() {
        assertThat(EventDispatch.missingHandlers(TestAccount.class, AccountEvent.class))
                .containsExactlyInAnyOrder(AccountEvent.Frozen.class, AccountEvent.Ignored.class);

        assertThat(EventDispatch.missingHandlers(FamilyHandler.class, AccountEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("非 sealed 类型校验被拒绝")
    void missingHandlersRejectsNonSealed() {
        assertThatThrownBy(() -> EventDispatch.missingHandlers(TestAccount.class, AccountEvent.Deposited.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a sealed");
    }

    @Test
    @DisplayName("handledEventTypes 与 table 一致")
    void handledTypesMatchTable() {
        assertThat(EventDispatch.handledEventTypes(TestAccount.class))
                .containsExactlyInAnyOrder(AccountEvent.Opened.class, AccountEvent.Deposited.class);
    }

    // ===================== 测试夹具 =====================

    /**
     * 只有父类型处理器的聚合。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    static final class FamilyHandler extends AggregateRoot<Long> implements EventApplier<AccountEvent> {

        private FamilyHandler(Long id) {
            super(id);
        }

        protected void apply(AccountEvent event) {
            // 父类型处理器：整族事件都在这里落地（穷尽 switch 写法同理）
        }
    }

    /**
     * 父类声明处理器、子类覆盖。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    static class ParentHandler extends AggregateRoot<Long> implements EventApplier<AccountEvent> {

        ParentHandler(Long id) {
            super(id);
        }

        protected void apply(AccountEvent.Opened event) {
            // 父类实现
        }
    }

    /**
     * 覆盖父类处理器的子类。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    static final class ChildHandler extends ParentHandler {

        private ChildHandler(Long id) {
            super(id);
        }

        @Override
        protected void apply(AccountEvent.Opened event) {
            // 子类覆盖
        }
    }

    /**
     * 抽象 apply 与静态 apply 的聚合。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    abstract static class AbstractHandler extends AggregateRoot<Long> implements EventApplier<AccountEvent> {

        private AbstractHandler(Long id) {
            super(id);
        }

        protected abstract void apply(AccountEvent.Opened event);

        protected static void apply(AccountEvent.Deposited event) {
            // 静态方法不是处理器
        }
    }

    /**
     * 参数不是事件的 apply。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    static final class WrongParameterHandler extends AggregateRoot<Long> implements EventApplier<AccountEvent> {

        private WrongParameterHandler(Long id) {
            super(id);
        }

        protected void apply(String text) {
            // 永远不会被分派
        }
    }

    /**
     * 未声明事件族的聚合。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    static final class PlainRoot extends AggregateRoot<Long> {

        private PlainRoot(Long id) {
            super(id);
        }

        protected void apply(AccountEvent.Opened event) {
            // 无 EventApplier 声明
        }
    }
}
