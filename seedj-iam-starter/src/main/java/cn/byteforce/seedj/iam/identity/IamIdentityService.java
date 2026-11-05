package cn.byteforce.seedj.iam.identity;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;

import java.time.Instant;
import java.util.Map;

/** Password authentication and user lifecycle use cases. */
public class IamIdentityService {

    private final IamUserRepository users;
    private final IamPasswordHasher hasher;
    private final IamTokenRevoker revoker;
    private final SecurityAuditSink audit;
    private final IdGenerator<String> idGenerator;
    private final String dummyHash;

    public IamIdentityService(IamUserRepository users, IamPasswordHasher hasher,
                              IamTokenRevoker revoker, SecurityAuditSink audit,
                              IdGenerator<String> idGenerator) {
        this.users = users;
        this.hasher = hasher;
        this.revoker = revoker;
        this.audit = audit;
        this.idGenerator = idGenerator;
        // A fixed hash makes unknown-user and wrong-password paths do comparable work.
        this.dummyHash = hasher.encode("seedj-dummy-password-not-used");
    }

    public IamUser authenticate(String tenantId, String userName, String rawPassword) {
        IamUser user = users.findByUserName(tenantId, userName).orElse(null);
        String hash = user == null ? dummyHash
                : users.findPasswordHash(user.id()).orElse(dummyHash);
        boolean matches = hasher.matches(rawPassword == null ? "" : rawPassword, hash);
        if (user == null || !matches) {
            audit(user, "auth.password", "FAILURE", "invalid credentials");
            throw new IamException(IamErrorCode.INVALID_CREDENTIALS, "invalid credentials");
        }
        if (!user.active()) {
            audit(user, "auth.password", "FAILURE", "account disabled");
            throw new IamException(IamErrorCode.ACCOUNT_DISABLED, "account disabled");
        }
        audit(user, "auth.password", "SUCCESS", "");
        return user;
    }

    public IamUser createUser(String tenantId, String userName, String rawPassword,
                              String displayName, String email) {
        IamUser user = IamUser.create(idGenerator.id(), tenantId, userName, displayName, email, true);
        users.create(user, rawPassword == null ? null : hasher.encode(rawPassword));
        audit(user, "iam.user.create", "SUCCESS", "");
        return user;
    }

    public IamUser requireUser(String userId) {
        return users.findById(userId).orElseThrow(
                () -> new IamException(IamErrorCode.USER_NOT_FOUND, "user not found: " + userId));
    }

    public IamUser update(IamUser user) {
        return users.update(user);
    }

    public void setActive(String userId, boolean active) {
        IamUser user = requireUser(userId);
        if (user.active() == active) {
            return;
        }
        users.update(user.withActive(active));
        if (!active) {
            revokeSessions(user);
        }
        audit(user, active ? "iam.user.enable" : "iam.user.disable", "SUCCESS", "");
    }

    public void changePassword(String userId, String rawPassword) {
        IamUser user = requireUser(userId);
        users.updatePassword(userId, hasher.encode(rawPassword), Instant.now());
        revokeSessions(user);
        audit(user, "iam.user.password", "SUCCESS", "");
    }

    public void deleteUser(String userId) {
        IamUser user = requireUser(userId);
        revokeSessions(user);
        users.delete(userId);
        audit(user, "iam.user.delete", "SUCCESS", "");
    }

    public void revokeSessions(IamUser user) {
        revoker.revokeAllForUser(user.tenantId(), user.userName());
    }

    public IamUserRepository users() {
        return users;
    }

    private void audit(IamUser user, String action, String outcome, String reason) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(new SecurityAuditEvent(idGenerator.id(),
                    user == null ? IamUser.DEFAULT_TENANT : user.tenantId(),
                    user == null ? "" : user.id(), action, "user",
                    user == null ? "" : user.id(), outcome, reason, Instant.now(),
                    Map.of("username", user == null ? "" : user.userName())));
        } catch (RuntimeException ignored) {
            // audit must never change authentication semantics
        }
    }
}
