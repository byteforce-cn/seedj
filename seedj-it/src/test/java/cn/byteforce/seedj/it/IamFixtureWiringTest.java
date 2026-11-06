package cn.byteforce.seedj.it;

import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.it.fixture.iam.IamTestUsers;
import cn.byteforce.seedj.server.iam.IamServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class IamFixtureWiringTest {

    @Test
    void fixtureUserDetailsWinsOverAutoConfiguration() {
        new WebApplicationContextRunner()
                .withUserConfiguration(IamServer.class, IamTestUsers.class)
                .withBean(IdGenerator.class, () -> {
                    java.util.concurrent.atomic.AtomicLong seq =
                            new java.util.concurrent.atomic.AtomicLong();
                    return (IdGenerator<String>) () -> "id-" + seq.incrementAndGet();
                })
                .withPropertyValues(
                        "seedj.iam.issuer=https://iam.test",
                        "seedj.iam.store=memory",
                        "seedj.iam.demo.enabled=true",
                        "seedj.pki.enabled=false",
                        "seedj.transit.enabled=false",
                        "seedj.eventbus.enabled=false",
                    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                        "seedj.id.enabled=false",
                        "seedj.audit.enabled=false",
                        "seedj.command.degraded-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserDetailsService service = context.getBean(UserDetailsService.class);
                    var alice = service.loadUserByUsername("alice");
                    PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                    assertThat(service)
                            .isInstanceOf(org.springframework.security.provisioning.InMemoryUserDetailsManager.class);
                    assertThat(encoder.matches("alice-password", alice.getPassword())).isTrue();
                });
    }
}
