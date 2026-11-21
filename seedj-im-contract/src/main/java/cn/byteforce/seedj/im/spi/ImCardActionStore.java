package cn.byteforce.seedj.im.spi;

import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImCardAction;
import java.util.Map;
import java.util.Optional;

/**
 * 卡片动作的幂等存储：{@code begin} 抢占，{@code complete} 写结果，{@code abandon} 允许重试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImCardActionStore {

    /**
     * 抢占一次动作。
     *
     * @param action 动作
     * @return 已完成时返回首次结果；首次抢占返回空
     * @throws ContractException {@code version_conflict}：同一动作正在执行
     */
    Optional<Map<String, Object>> begin(ImCardAction action);

    /**
     * 写入成功结果。
     *
     * @param action 动作
     * @param result 结果
     */
    void complete(ImCardAction action, Map<String, Object> result);

    /**
     * 放弃一次未完成的动作，允许重试。
     *
     * @param action 动作
     */
    void abandon(ImCardAction action);
}
