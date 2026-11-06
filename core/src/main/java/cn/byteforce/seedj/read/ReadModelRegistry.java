package cn.byteforce.seedj.read;

import java.util.List;
import java.util.Optional;

/**
 * 读模型注册表：按对象类型查找声明。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ReadModelRegistry {

    /**
     * 查找对象读模型。
     *
     * @param objectType 对象类型
     * @return 读模型；未知为空
     */
    Optional<ReadModel> find(String objectType);

    /**
     * 全部读模型，按声明顺序。
     *
     * @return 不可变列表
     */
    List<ReadModel> all();
}
