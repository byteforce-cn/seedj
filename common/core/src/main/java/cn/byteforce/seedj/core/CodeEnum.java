package cn.byteforce.seedj.core;

/**
 * code 语义枚举契约：枚举通过实现本接口获得「业务 code ↔ 枚举」的稳定映射。
 *
 * <p>全仓的 code 契约由三处消费：</p>
 * <ul>
 *   <li>codegen（{@code @GenerateCodeEnum}）生成静态工厂与索引；</li>
 *   <li>starter-core 的 Jackson 定制：序列化输出 {@code code}、反序列化按 {@code code} 命中；</li>
 *   <li>starter-data-mybatis 的 TypeHandler：数据库按 {@code code} 读写。</li>
 * </ul>
 *
 * @param <C> code 类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface CodeEnum<C> {

    /**
     * 返回业务 code。
     *
     * @return 业务 code
     */
    C getCode();

    /**
     * 返回人类可读的 label（用于默认错误消息等场景，不参与 wire 契约）。
     *
     * @return 描述文本
     */
    String getLabel();

}