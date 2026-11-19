package cn.byteforce.seedj.starter.im;

import java.util.Map;

/**
 * 卡片动作处理器：按卡片 schema 注册，IM 层只负责鉴权与幂等。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImCardActionHandler {

    /**
     * 负责的卡片 schema。
     *
     * @return schema
     */
    String schema();

    /**
     * 处理一次动作。
     *
     * @param context 上下文
     * @return 结果（原样回传并落库）
     */
    Map<String, Object> handle(ImCardActionContext context);
}
