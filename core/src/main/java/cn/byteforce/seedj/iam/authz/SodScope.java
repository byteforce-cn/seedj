package cn.byteforce.seedj.iam.authz;

/**
 * SoD 作用域比较维度。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum SodScope {

    SAME_DEPARTMENT("department"),
    SAME_ORGANIZATION("organization"),
    SAME_WAREHOUSE("warehouse");

    private final String scopeKey;

    SodScope(String scopeKey) {
        this.scopeKey = scopeKey;
    }

    /**
     * 作用域键。
     *
     * @return department / organization / warehouse
     */
    public String scopeKey() {
        return scopeKey;
    }
}
