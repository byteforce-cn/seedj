package cn.byteforce.seedj.starter.iam.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** Empty-start in-memory client repository; clients are inserted by the bootstrap runner. */
public class IamInMemoryRegisteredClientRepository implements RegisteredClientRepository {

    private final Map<String, RegisteredClient> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByClientId = new ConcurrentHashMap<>();

    @Override
    public void save(RegisteredClient registeredClient) {
        byId.put(registeredClient.getId(), registeredClient);
        idByClientId.put(registeredClient.getClientId(), registeredClient.getId());
    }

    @Override
    public RegisteredClient findById(String id) {
        return byId.get(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        String id = idByClientId.get(clientId);
        return id == null ? null : byId.get(id);
    }
}
