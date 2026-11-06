package cn.byteforce.seedj.iam.authz;

import java.util.List;

/**
 * SoD 规则集；默认内置 SOD-001..003。
 *
 * @param rules 规则
 * @author ByteForce
 * @since 1.0.0
 */
public record SodRuleSet(List<SodRule> rules) {

    public SodRuleSet {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * 规范内置规则。
     *
     * @return 默认规则集
     */
    public static SodRuleSet defaults() {
        return new SodRuleSet(List.of(
                new SodRule("SOD-001", "采购申请与审批分离",
                        List.of("purchase.request.create", "purchase.request.approve"),
                        SodScope.SAME_DEPARTMENT, SodEnforcement.PREVENT),
                new SodRule("SOD-002", "付款与审批分离",
                        List.of("payment.create", "payment.approve"),
                        SodScope.SAME_ORGANIZATION, SodEnforcement.PREVENT),
                new SodRule("SOD-003", "库存盘点与调整分离",
                        List.of("inventory.count", "inventory.adjust"),
                        SodScope.SAME_WAREHOUSE, SodEnforcement.WARN)));
    }
}
