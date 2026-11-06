package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.iam.IamSigningKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 内存 JWK 仓库：初始只有一把可签名活动密钥，轮换后旧密钥只保留公钥。 */
class InMemoryIamSigningKeyStoreTest {

    private final AtomicInteger sequence = new AtomicInteger();
    private final IdGenerator<String> ids = () -> "kid-" + sequence.incrementAndGet();

    @Test
    void startsWithSingleActiveSigningKey() {
        InMemoryIamSigningKeyStore store = new InMemoryIamSigningKeyStore(ids);

        IamSigningKey active = store.activeKey();

        assertThat(active.kid()).isEqualTo("kid-1");
        assertThat(active.active()).isTrue();
        assertThat(active.canSign()).isTrue();
        assertThat(active.algorithm()).isEqualTo("RS256");
        assertThat(active.publicJwk()).contains("\"kty\":\"RSA\"").contains("\"use\":\"sig\"");
        assertThat(active.privateJwk()).contains("\"d\":");
        assertThat(store.keys()).containsExactly(active);
    }

    @Test
    void rotateRetiresPreviousKeyAndPublishesBoth() {
        InMemoryIamSigningKeyStore store = new InMemoryIamSigningKeyStore(ids);
        IamSigningKey first = store.activeKey();

        IamSigningKey second = store.rotate();

        assertThat(second.kid()).isEqualTo("kid-2");
        assertThat(store.activeKey()).isSameAs(second);
        List<IamSigningKey> keys = store.keys();
        assertThat(keys).extracting(IamSigningKey::kid).containsExactly("kid-2", "kid-1");
        IamSigningKey retired = keys.get(1);
        assertThat(retired.active()).isFalse();
        assertThat(retired.canSign()).isFalse();
        assertThat(retired.privateJwk()).isEmpty();
        assertThat(retired.retiredAt()).isNotNull();
        assertThat(retired.publicJwk()).isEqualTo(first.publicJwk());
    }

    @Test
    void rotateKeepsOnlyPublicMaterialForRetiredKeys() {
        InMemoryIamSigningKeyStore store = new InMemoryIamSigningKeyStore(ids);

        store.rotate();
        store.rotate();

        assertThat(store.keys()).hasSize(3);
        assertThat(store.keys()).filteredOn(key -> !key.active())
                .allSatisfy(key -> assertThat(key.privateJwk()).isEmpty());
        assertThat(store.keys()).filteredOn(IamSigningKey::active).hasSize(1);
    }
}
