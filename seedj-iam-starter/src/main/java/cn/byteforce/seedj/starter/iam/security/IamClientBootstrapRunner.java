package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.starter.iam.config.IamProperties;
import cn.byteforce.seedj.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.List;

/** Inserts configured/demo clients if they are not already persisted. */
public class IamClientBootstrapRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IamClientBootstrapRunner.class);

    private final RegisteredClientRepository repository;
    private final List<RegisteredClient> clients;

    public IamClientBootstrapRunner(RegisteredClientRepository repository,
                                    IamProperties properties,
                                    IdGenerator<String> idGenerator) {
        this.repository = repository;
        this.clients = IamClientFactory.fromProperties(properties, idGenerator);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (clients.isEmpty()) {
            log.warn("no OAuth2 clients registered; set seedj.iam.clients or "
                    + "seedj.iam.demo.enabled=true");
            return;
        }
        for (RegisteredClient client : clients) {
            if (repository.findByClientId(client.getClientId()) == null) {
                repository.save(client);
                log.info("registered OAuth2 client '{}'", client.getClientId());
            }
        }
    }
}
