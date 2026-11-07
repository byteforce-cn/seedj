package cn.byteforce.seedj.starter.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuditSink;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class IamIdentityServiceTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamTokenRevoker revoker = new InMemoryIamTokenRevoker();
    private final InMemoryIamAuditSink audit = new InMemoryIamAuditSink();
    private final IamPasswordHasher hasher =
            new IamPasswordHasherAdapter(
                    PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final AtomicLong seq = new AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "id-" + seq.incrementAndGet();
    private final IamIdentityService service =
            new IamIdentityService(users, hasher, revoker, audit, idGenerator);

    @Test
    void authenticatesActiveUserAndAuditsSuccess() {
        IamUser user = service.createUser("default", "alice", "alice-password", "Alice", null);

        IamUser authenticated = service.authenticate("default", "alice", "alice-password");

        assertThat(authenticated.id()).isEqualTo(user.id());
        SecurityAuditEvent event = audit.events().get(audit.events().size() - 1);
        assertThat(event.action()).isEqualTo("auth.password");
        assertThat(event.outcome()).isEqualTo("SUCCESS");
        assertThat(event.actorId()).isEqualTo(user.id());
        assertThat(event.details()).containsEntry("username", "alice");
    }

    @Test
    void rejectsUnknownUserWithInvalidCredentials() {
        assertThatThrownBy(() -> service.authenticate("default", "ghost", "whatever"))
                .isInstanceOf(IamException.class)
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(IamErrorCode.INVALID_CREDENTIALS);

        SecurityAuditEvent event = audit.events().get(audit.events().size() - 1);
        assertThat(event.outcome()).isEqualTo("FAILURE");
        assertThat(event.reason()).isEqualTo("invalid credentials");
        assertThat(event.actorId()).isEmpty();
        assertThat(event.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
    }

    @Test
    void rejectsWrongPasswordAndNullPassword() {
        service.createUser("default", "alice", "alice-password", "Alice", null);

        assertThatThrownBy(() -> service.authenticate("default", "alice", "wrong"))
                .isInstanceOf(IamException.class)
                .hasMessage("invalid credentials");
        assertThatThrownBy(() -> service.authenticate("default", "alice", null))
                .isInstanceOf(IamException.class)
                .hasMessage("invalid credentials");
        assertThat(audit.events()).hasSize(3);
    }

    @Test
    void rejectsDisabledUserAfterPasswordCheck() {
        IamUser user = service.createUser("default", "alice", "alice-password", "Alice", null);
        users.update(user.withActive(false));

        assertThatThrownBy(() -> service.authenticate("default", "alice", "alice-password"))
                .isInstanceOf(IamException.class)
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(IamErrorCode.ACCOUNT_DISABLED);

        SecurityAuditEvent event = audit.events().get(audit.events().size() - 1);
        assertThat(event.action()).isEqualTo("auth.password");
        assertThat(event.reason()).isEqualTo("account disabled");
    }

    @Test
    void createsUserWithoutPassword() {
        IamUser user = service.createUser("default", "bob", null, "Bob", "bob@example.com");

        assertThat(users.findPasswordHash(user.id())).isEmpty();
        assertThat(user.email()).isEqualTo("bob@example.com");
        assertThat(service.users().findById(user.id())).isPresent();
    }

    @Test
    void setActiveIsNoOpWhenStateMatches() {
        IamUser user = service.createUser("default", "alice", "alice-password", "Alice", null);
        int audited = audit.events().size();

        service.setActive(user.id(), true);

        assertThat(audit.events()).hasSize(audited);
        assertThat(revoker.revokedPrincipals()).isEmpty();
    }

    @Test
    void disablingRevokesSessionsAndEnablingDoesNot() {
        IamUser user = service.createUser("default", "alice", "alice-password", "Alice", null);

        service.setActive(user.id(), false);
        assertThat(users.findById(user.id()).orElseThrow().active()).isFalse();
        assertThat(revoker.isRevoked("alice")).isTrue();
        assertThat(audit.events().get(audit.events().size() - 1).action())
                .isEqualTo("iam.user.disable");

        service.setActive(user.id(), true);
        assertThat(users.findById(user.id()).orElseThrow().active()).isTrue();
        assertThat(audit.events().get(audit.events().size() - 1).action())
                .isEqualTo("iam.user.enable");
    }

    @Test
    void changePasswordReplacesHashAndRevokesSessions() {
        IamUser user = service.createUser("default", "alice", "alice-password", "Alice", null);

        service.changePassword(user.id(), "new-password");

        assertThat(hasher.matches("new-password", users.findPasswordHash(user.id()).orElseThrow()))
                .isTrue();
        assertThat(
                        hasher.matches(
                                "alice-password", users.findPasswordHash(user.id()).orElseThrow()))
                .isFalse();
        assertThat(revoker.isRevoked("alice")).isTrue();
        assertThat(audit.events().get(audit.events().size() - 1).action())
                .isEqualTo("iam.user.password");
    }

    @Test
    void deleteUserRevokesThenRemoves() {
        IamUser user = service.createUser("default", "alice", "alice-password", "Alice", null);

        service.deleteUser(user.id());

        assertThat(users.findById(user.id())).isEmpty();
        assertThat(revoker.isRevoked("alice")).isTrue();
        assertThat(audit.events().get(audit.events().size() - 1).action())
                .isEqualTo("iam.user.delete");
    }

    @Test
    void requireUserFailsForUnknownId() {
        assertThatThrownBy(() -> service.requireUser("missing"))
                .isInstanceOf(IamException.class)
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(IamErrorCode.USER_NOT_FOUND);
    }

    @Test
    void updateDelegatesToRepository() {
        IamUser user = service.createUser("default", "alice", "alice-password", "Alice", null);

        IamUser updated =
                service.update(
                        user.withProfile(
                                "Alice Ng",
                                "alice@example.com",
                                java.util.Map.of("givenName", "Alice")));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(users.findById(user.id()).orElseThrow().displayName()).isEqualTo("Alice Ng");
    }

    @Test
    void auditFailureNeverChangesAuthenticationOutcome() {
        SecurityAuditSink failing =
                event -> {
                    throw new IllegalStateException("audit backend down");
                };
        IamIdentityService serviceWithFailingAudit =
                new IamIdentityService(users, hasher, revoker, failing, idGenerator);
        IamUser user =
                serviceWithFailingAudit.createUser(
                        "default", "alice", "alice-password", "Alice", null);

        assertThat(serviceWithFailingAudit.authenticate("default", "alice", "alice-password").id())
                .isEqualTo(user.id());
    }

    @Test
    void nullAuditSinkIsTolerated() {
        IamIdentityService serviceWithoutAudit =
                new IamIdentityService(users, hasher, revoker, null, idGenerator);
        serviceWithoutAudit.createUser("default", "alice", "alice-password", "Alice", null);

        assertThat(serviceWithoutAudit.authenticate("default", "alice", "alice-password"))
                .isNotNull();
        assertThat(audit.events()).isEmpty();
    }
}
