package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.IamSigningKey;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.id.IdGenerator;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * In-memory RSA signing keys. Not persistent: every restart creates a new active
 * key, which invalidates previously issued tokens. Use the JDBC store in production.
 */
public class InMemoryIamSigningKeyStore implements IamSigningKeyStore {

    private final Object lock = new Object();
    private final List<IamSigningKey> keys = new ArrayList<>();
    private final IdGenerator<String> idGenerator;
    private IamSigningKey active;

    public InMemoryIamSigningKeyStore(IdGenerator<String> idGenerator) {
        this.idGenerator = idGenerator;
        this.active = generate();
        keys.add(active);
    }

    @Override
    public IamSigningKey activeKey() {
        synchronized (lock) {
            return active;
        }
    }

    @Override
    public List<IamSigningKey> keys() {
        synchronized (lock) {
            return List.copyOf(keys);
        }
    }

    @Override
    public IamSigningKey rotate() {
        synchronized (lock) {
            IamSigningKey retired = active.retire(Instant.now());
            keys.remove(active);
            keys.add(0, retired);
            IamSigningKey created = generate();
            keys.add(0, created);
            active = created;
            return created;
        }
    }

    private IamSigningKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) pair.getPrivate();
            String kid = idGenerator.id();
            String publicJwk = publicJwk(kid, publicKey);
            String privateJwk = privateJwk(kid, publicKey, privateKey);
            return new IamSigningKey(kid, "RS256", publicJwk, privateJwk, true,
                    Instant.now(), null);
        } catch (Exception e) {
            throw new IllegalStateException("unable to generate RSA signing key", e);
        }
    }

    private static String publicJwk(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + b64(key.getModulus()) + "\",\"e\":\"" + b64(key.getPublicExponent()) + "\"}";
    }

    private static String privateJwk(String kid, RSAPublicKey publicKey, RSAPrivateCrtKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + b64(publicKey.getModulus()) + "\","
                + "\"e\":\"" + b64(publicKey.getPublicExponent()) + "\","
                + "\"d\":\"" + b64(key.getPrivateExponent()) + "\","
                + "\"p\":\"" + b64(key.getPrimeP()) + "\","
                + "\"q\":\"" + b64(key.getPrimeQ()) + "\","
                + "\"dp\":\"" + b64(key.getPrimeExponentP()) + "\","
                + "\"dq\":\"" + b64(key.getPrimeExponentQ()) + "\","
                + "\"qi\":\"" + b64(key.getCrtCoefficient()) + "\"}";
    }

    private static String b64(BigInteger value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(value));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
