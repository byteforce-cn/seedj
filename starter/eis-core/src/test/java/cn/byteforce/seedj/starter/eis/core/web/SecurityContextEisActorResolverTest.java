package cn.byteforce.seedj.starter.eis.core.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecurityContextEisActorResolver} 解析语义测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class SecurityContextEisActorResolverTest {

    private final SecurityContextEisActorResolver resolver = new SecurityContextEisActorResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesUuidPrincipalName() {
        UUID actor = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(resolver.currentActor()).contains(actor);
    }

    @Test
    void resolvesNothingWithoutAuthentication() {
        SecurityContextHolder.clearContext();

        assertThat(resolver.currentActor()).isEmpty();
    }

    @Test
    void resolvesNothingForAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(resolver.currentActor()).isEmpty();
    }

    @Test
    void resolvesNothingForNonUuidName() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(resolver.currentActor()).isEmpty();
    }

    @Test
    void resolvesNothingForUnauthenticatedToken() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("name", "credentials"));

        assertThat(resolver.currentActor()).isEmpty();
    }
}
