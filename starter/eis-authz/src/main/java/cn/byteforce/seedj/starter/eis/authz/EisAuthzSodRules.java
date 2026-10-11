package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzSodRule;

import java.util.List;

/**
 * 04 契约 {@code sod.rules.yaml} 的内置缺省规则（冻结语义的运行时镜像）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuthzSodRules {

    private EisAuthzSodRules() {
    }

    /**
     * 契约缺省规则（SOD-001/002/003）。
     *
     * @return 规则列表
     */
    public static List<EisAuthzSodRule> contractDefaults() {
        return List.of(
                new EisAuthzSodRule("SOD-001", "采购申请与审批分离",
                        List.of("purchase.request.create", "purchase.request.approve"), "same_department",
                        "prevent"),
                new EisAuthzSodRule("SOD-002", "付款与审批分离",
                        List.of("payment.create", "payment.approve"), "same_organization", "prevent"),
                new EisAuthzSodRule("SOD-003", "库存盘点与调整分离",
                        List.of("inventory.count", "inventory.adjust"), "same_warehouse", "warn"));
    }
}
