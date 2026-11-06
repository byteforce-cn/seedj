package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.PolicyCondition;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IamPolicyRepository} 契约测试套件：ABAC 策略、版本冲突与授权 epoch。
 */
public abstract class IamPolicyRepositoryContractTest {

    protected abstract IamPolicyRepository repository();

    @Test
    void savesAndFindsPolicy() {
        IamPolicy policy = IamPolicy.allow("p1", "default", "allow-read",
                Set.of("read"), Set.of("employee"), null);

        repository().save(policy);

        assertThat(repository().findById("p1")).contains(policy);
        assertThat(repository().findAll("default")).containsExactly(policy);
    }

    @Test
    void rejectsStaleVersion() {
        repository().save(IamPolicy.allow("p1", "default", "allow-read",
                Set.of("read"), Set.of("employee"), null));
        IamPolicy stale = new IamPolicy("p1", "default", "allow-read", IamPolicyEffect.ALLOW, 0,
                Set.of("read"), Set.of("employee"), PolicyCondition.Always.INSTANCE, true, 5);

        assertThatThrownBy(() -> repository().save(stale))
                .isInstanceOfSatisfying(IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.CONFLICT));
    }

    @Test
    void findsOnlyEnabledPoliciesOfTenantOrderedByPriority() {
        repository().save(new IamPolicy("low", "default", "low", IamPolicyEffect.ALLOW, 1,
                Set.of("read"), Set.of("employee"), PolicyCondition.Always.INSTANCE, true, 0));
        repository().save(new IamPolicy("high", "default", "high", IamPolicyEffect.DENY, 9,
                Set.of("read"), Set.of("employee"), PolicyCondition.Always.INSTANCE, true, 0));
        repository().save(new IamPolicy("off", "default", "off", IamPolicyEffect.ALLOW, 5,
                Set.of("read"), Set.of("employee"), PolicyCondition.Always.INSTANCE, false, 0));
        repository().save(new IamPolicy("other", "other", "other", IamPolicyEffect.ALLOW, 7,
                Set.of("read"), Set.of("employee"), PolicyCondition.Always.INSTANCE, true, 0));

        assertThat(repository().findEnabled("default"))
                .extracting(IamPolicy::id).containsExactly("high", "low");
        assertThat(repository().findAll("default"))
                .extracting(IamPolicy::id).containsExactly("high", "low", "off");
    }

    @Test
    void deleteRemovesPolicy() {
        repository().save(IamPolicy.allow("p1", "default", "allow-read",
                Set.of("read"), Set.of("employee"), null));

        repository().delete("p1");

        assertThat(repository().findById("p1")).isEmpty();
        assertThat(repository().findAll("default")).isEmpty();
    }

    @Test
    void deletingUnknownPolicyIsNotAnError() {
        repository().delete("missing");

        assertThat(repository().findAll("default")).isEmpty();
    }

    @Test
    void epochAdvancesPerTenant() {
        long before = repository().currentEpoch("default");
        long otherBefore = repository().currentEpoch("other");

        repository().save(IamPolicy.allow("p1", "default", "allow-read",
                Set.of("read"), Set.of("employee"), null));

        assertThat(repository().currentEpoch("default")).isGreaterThan(before);
        assertThat(repository().currentEpoch("other")).isEqualTo(otherBefore);
    }
}
