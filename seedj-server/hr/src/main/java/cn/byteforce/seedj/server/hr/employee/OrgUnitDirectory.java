package cn.byteforce.seedj.server.hr.employee;

/**
 * 组织单元存在性检查：HR 由自己的组织服务实现；测试提供 stub。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface OrgUnitDirectory {

    /**
     * 组织单元是否存在。
     *
     * @param orgUnitId 组织单元 ID
     * @return 存在为 {@code true}
     */
    boolean exists(String orgUnitId);
}
