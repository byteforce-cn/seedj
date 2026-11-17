package cn.byteforce.seedj.starter.im;

/** 连接上线/下线的监听点：握手层只发事件，不依赖会话存储。 */
interface ImPresenceListener {

    /**
     * 某主体的在线状态发生变化。
     *
     * @param tenantId 租户
     * @param subjectId 主体
     * @param online 是否还有活动连接
     */
    void changed(String tenantId, String subjectId, boolean online);
}
