package cn.byteforce.seedj.starter.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.IamSigningKey;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IamJwkAdminControllerTest {

    private final IamSigningKey retired =
            new IamSigningKey(
                    "kid-old",
                    "RS256",
                    "{\"kty\":\"RSA\"}",
                    "",
                    false,
                    Instant.parse("2025-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:00Z"));
    private final IamSigningKey active =
            new IamSigningKey(
                    "kid-new",
                    "RS256",
                    "{\"kty\":\"RSA\"}",
                    "{\"kty\":\"RSA\",\"d\":\"x\"}",
                    true,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null);
    private final IamJwkAdminController controller =
            new IamJwkAdminController(new FakeKeyStore(List.of(retired, active), active));

    @Test
    void listsPublishedKeysWithRetirementState() {
        Map<String, Object> body = controller.keys();

        assertThat((List<?>) body.get("keys")).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
        assertThat(keys.get(0))
                .containsEntry("kid", "kid-old")
                .containsEntry("algorithm", "RS256")
                .containsEntry("active", false)
                .containsEntry("retiredAt", "2026-01-01T00:00:00Z");
        assertThat(keys.get(1))
                .containsEntry("kid", "kid-new")
                .containsEntry("active", true)
                .containsEntry("retiredAt", "");
    }

    @Test
    void rotatesThroughTheKeyStore() {
        FakeKeyStore store =
                new FakeKeyStore(
                        List.of(active),
                        new IamSigningKey(
                                "kid-rotated",
                                "RS256",
                                "{\"kty\":\"RSA\"}",
                                "{\"kty\":\"RSA\",\"d\":\"x\"}",
                                true,
                                Instant.now(),
                                null));

        Map<String, Object> body = new IamJwkAdminController(store).rotate();

        assertThat(body).containsEntry("kid", "kid-rotated").containsEntry("active", true);
        assertThat(store.rotations).isEqualTo(1);
    }

    private static final class FakeKeyStore implements IamSigningKeyStore {

        private final List<IamSigningKey> keys;
        private final IamSigningKey rotated;
        private int rotations;

        private FakeKeyStore(List<IamSigningKey> keys, IamSigningKey rotated) {
            this.keys = keys;
            this.rotated = rotated;
        }

        @Override
        public IamSigningKey activeKey() {
            return keys.get(keys.size() - 1);
        }

        @Override
        public List<IamSigningKey> keys() {
            return keys;
        }

        @Override
        public IamSigningKey rotate() {
            rotations++;
            return rotated;
        }
    }
}
