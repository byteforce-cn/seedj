package cn.byteforce.seedj.iam.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SoD 冲突求值：按作用域键比较冲突权限的授予范围。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class SodEvaluator {

    private SodEvaluator() {
    }

    /**
     * 计算冲突。
     *
     * @param rules            规则
     * @param permissionScopes 权限 → 授予范围列表
     * @return 每条规则至多一条 finding
     */
    public static List<SodFinding> findings(List<SodRule> rules,
                                            Map<String, List<Map<String, String>>> permissionScopes) {
        List<SodFinding> findings = new ArrayList<>();
        for (SodRule rule : rules) {
            if (conflicts(rule, permissionScopes)) {
                findings.add(new SodFinding(rule.id(), rule.enforcement(),
                        rule.conflictingPermissions()));
            }
        }
        return findings;
    }

    private static boolean conflicts(SodRule rule,
                                     Map<String, List<Map<String, String>>> permissionScopes) {
        List<List<Map<String, String>>> grants = new ArrayList<>();
        for (String permission : rule.conflictingPermissions()) {
            List<Map<String, String>> scopes = permissionScopes.get(permission);
            if (scopes == null || scopes.isEmpty()) {
                return false;
            }
            grants.add(scopes);
        }
        String key = rule.scope().scopeKey();
        for (int i = 0; i < grants.size(); i++) {
            for (int j = i + 1; j < grants.size(); j++) {
                if (sharesScope(grants.get(i), grants.get(j), key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sharesScope(List<Map<String, String>> left,
                                       List<Map<String, String>> right, String key) {
        for (Map<String, String> first : left) {
            String value = first.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            for (Map<String, String> second : right) {
                if (value.equals(second.get(key))) {
                    return true;
                }
            }
        }
        return false;
    }
}
