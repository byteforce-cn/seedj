package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * EIS 授权服务远程客户端（其他服务校验/解析调用）。
 *
 * <p>调用权威服务的 {@code /resolve} 与 {@code /check}；建议经
 * {@link EisAuthzCachingOperations} 叠进程内 TTL 缓存。错误一律抛出
 * {@link EisAuthzRemoteException}（fail-open 由调用方决定：命令受理链路拒绝 /
 * 失效加速链路可降级）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuthzClient implements EisAuthzOperations {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;

    /**
     * 构造客户端。
     *
     * @param restClient 已配置基址的 RestClient
     */
    public EisAuthzClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public EisAuthzResolution resolve(String principalRef, Instant asOf, Instant recordedAsOf,
                                      JsonNode scopeFilter) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("principal", principalRef);
        body.put("as_of", asOf.toString());
        if (recordedAsOf != null) {
            body.put("recorded_as_of", recordedAsOf.toString());
        }
        if (scopeFilter != null && !scopeFilter.isNull()) {
            body.set("scope", scopeFilter);
        }
        JsonNode response = post("/resolve", body);
        UUID principal = response.get("principal") == null ? null
                : UUID.fromString(response.get("principal").stringValue());
        Instant resolvedAsOf = response.get("as_of") == null ? asOf
                : Instant.parse(response.get("as_of").stringValue());
        return new EisAuthzResolution(principal, resolvedAsOf, stringList(response.get("roles")),
                stringList(response.get("permissions")), stringList(response.get("sod_warnings")));
    }

    @Override
    public EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("principal", principalRef);
        body.put("permission", permission);
        body.put("as_of", asOf.toString());
        if (recordedAsOf != null) {
            body.put("recorded_as_of", recordedAsOf.toString());
        }
        JsonNode response = post("/check", body);
        boolean allowed = response.get("allowed") != null && response.get("allowed").booleanValue();
        String reason = response.get("reason") == null ? null : response.get("reason").stringValue();
        String reasonCode = response.get("reason_code") == null ? null
                : response.get("reason_code").stringValue();
        return new EisAuthzDecision(allowed, reason, reasonCode, stringList(response.get("warnings")));
    }

    private JsonNode post(String path, JsonNode body) {
        String payload = restClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    String text = new String(response.getBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    throw new EisAuthzRemoteException(response.getStatusCode().value(), path, text);
                })
                .body(String.class);
        return MAPPER.readTree(payload);
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asString());
        }
        return values;
    }
}
