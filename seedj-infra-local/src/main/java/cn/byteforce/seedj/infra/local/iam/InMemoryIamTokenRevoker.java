package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers revoked principals so an authorization-service decorator can reject stale in-memory
 * authorizations. JDBC mode deletes rows instead.
 */
public class InMemoryIamTokenRevoker implements IamTokenRevoker {

    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    @Override
    public void revokeAllForUser(String tenantId, String principalName) {
        revoked.add(principalName);
    }

    public boolean isRevoked(String principalName) {
        return revoked.contains(principalName);
    }

    public Set<String> revokedPrincipals() {
        return Set.copyOf(revoked);
    }
}
