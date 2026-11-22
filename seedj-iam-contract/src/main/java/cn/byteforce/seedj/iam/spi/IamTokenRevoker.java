package cn.byteforce.seedj.iam.spi;

/**
 * Immediately invalidates all refresh/authorization records for a principal.
 *
 * <p>Revocation is primarily forward-only: implementations are required to drop what they can drop.
 * A store that keeps its own revocation list may additionally answer {@link #isRevoked} so read
 * paths can hide already-issued records; the default keeps the contract usable by stores whose
 * revocation is a delete (nothing left to hide).
 */
public interface IamTokenRevoker {

    void revokeAllForUser(String tenantId, String principalName);

    /**
     * Whether the principal is currently revoked in a store that keeps a revocation list.
     *
     * @param principalName principal (user name)
     * @return {@code true} only when the implementation can prove the principal is revoked
     */
    default boolean isRevoked(String principalName) {
        return false;
    }
}
