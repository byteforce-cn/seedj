package cn.byteforce.seedj.iam.security;

import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** JWK operations for rotation; public keys stay available until expiry. */
@RestController
@RequestMapping(path = "/iam/admin/jwk", produces = MediaType.APPLICATION_JSON_VALUE)
public class IamJwkAdminController {

    private final IamSigningKeyStore keys;

    public IamJwkAdminController(IamSigningKeyStore keys) {
        this.keys = keys;
    }

    @GetMapping
    public Map<String, Object> keys() {
        List<Map<String, Object>> values = keys.keys().stream()
                .map(key -> Map.<String, Object>of(
                        "kid", key.kid(),
                        "algorithm", key.algorithm(),
                        "active", key.active(),
                        "retiredAt", key.retiredAt() == null ? "" : key.retiredAt().toString()))
                .toList();
        return Map.of("keys", values);
    }

    @PostMapping("/rotate")
    public Map<String, Object> rotate() {
        var active = keys.rotate();
        return Map.of("kid", active.kid(), "active", true);
    }
}
