package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * EIS 授权服务远程客户端（其他服务校验/解析调用）。
 *
 * <p>调用权威服务的 {@code /resolve} 与 {@code /check}；建议经
 * {@link EisAuthzCachingOperations} 叠进程内 TTL 缓存。配置了服务凭据
 * （{@link EisAuthzTokenProvider}，默认 client_credentials）时自动携带
 * {@code Authorization: Bearer}；未配置时保持明文直连行为不变。错误一律抛出
 * {@link EisAuthzRemoteException}（fail-open 由调用方决定：命令受理链路拒绝 /
 * 失效加速链路可降级）；网络层失败（连接/读取超时、连接拒绝）同样 fail-closed 抛出，
 * 超时以本地判定状态码 {@code 504} + 消息「超时」区别于远端 4xx/5xx。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuthzClient implements EisAuthzOperations {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;

    private final EisAuthzTokenProvider tokenProvider;

    /**
     * 构造客户端（无服务认证）。
     *
     * @param restClient 已配置基址的 RestClient
     */
    public EisAuthzClient(RestClient restClient) {
        this(restClient, null);
    }

    /**
     * 构造客户端（带服务令牌提供者）。
     *
     * @param restClient    已配置基址的 RestClient
     * @param tokenProvider 令牌提供者；{@code null} 等同无服务认证（明文/内网直连场景）
     */
    public EisAuthzClient(RestClient restClient, EisAuthzTokenProvider tokenProvider) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
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
        return parseDecision(post("/check", checkBody(principalRef, permission, asOf, recordedAsOf)));
    }

    /**
     * 检查权限（携带属性条件与调用方快照）。
     *
     * <p>传输面与权威服务 {@code POST /check} 对齐：条件序列化为 {@code require_attribute}
     * （seedj 扩展字段），快照序列化为契约可选字段 {@code attributes}；响应
     * {@code attribute_state} 回填到判定结果（未知字面量按缺失处理——{@code allowed}
     * 才是判定权威，诊断字段不得把已给出的判定炸掉）。</p>
     *
     * <p>无条件且无快照时委托四参 {@link #check(String, String, Instant, Instant)}：
     * 请求体与旧版逐字一致（不出现空数组）。</p>
     *
     * @param principalRef     主体引用（ID 或 key）
     * @param permission       权限串
     * @param asOf             业务时点
     * @param recordedAsOf     系统时点（可空）
     * @param conditions       属性条件（可空/空 = 不涉及属性）
     * @param providedSnapshot 调用方已拉取的快照（可空 = 判定方自行拉取）
     * @return 判定结果（含 {@code attribute_state}）
     */
    @Override
    public EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf,
                                  List<EisAuthzAttributeCondition> conditions,
                                  EisAuthzAttributeSnapshot providedSnapshot) {
        boolean hasConditions = conditions != null && !conditions.isEmpty();
        if (!hasConditions && providedSnapshot == null) {
            return check(principalRef, permission, asOf, recordedAsOf);
        }
        ObjectNode body = checkBody(principalRef, permission, asOf, recordedAsOf);
        if (hasConditions) {
            ArrayNode requireAttribute = body.putArray("require_attribute");
            for (EisAuthzAttributeCondition condition : conditions) {
                requireAttribute.add(condition.toJson());
            }
        }
        if (providedSnapshot != null) {
            body.set("attributes", providedSnapshot.toAttributeContext());
        }
        return parseDecision(post("/check", body));
    }

    private static ObjectNode checkBody(String principalRef, String permission, Instant asOf,
                                        Instant recordedAsOf) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("principal", principalRef);
        body.put("permission", permission);
        body.put("as_of", asOf.toString());
        if (recordedAsOf != null) {
            body.put("recorded_as_of", recordedAsOf.toString());
        }
        return body;
    }

    private static EisAuthzDecision parseDecision(JsonNode response) {
        boolean allowed = response.get("allowed") != null && response.get("allowed").booleanValue();
        String reason = response.get("reason") == null ? null : response.get("reason").stringValue();
        String reasonCode = response.get("reason_code") == null ? null
                : response.get("reason_code").stringValue();
        return new EisAuthzDecision(allowed, reason, reasonCode, stringList(response.get("warnings")),
                attributeState(response));
    }

    private static EisAuthzAttributeState attributeState(JsonNode response) {
        JsonNode node = response.get("attribute_state");
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return EisAuthzAttributeState.fromCode(node.stringValue());
        } catch (IllegalArgumentException ignored) {
            // 协议外字面量：判定权威是 allowed，诊断字段按缺失处理，不改变判定
            return null;
        }
    }

    private JsonNode post(String path, JsonNode body) {
        RestClient.RequestBodySpec spec = restClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (tokenProvider != null) {
            tokenProvider.accessToken().ifPresent(token ->
                    spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        }
        String payload;
        try {
            payload = spec.body(body.toString())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String text = new String(response.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        throw new EisAuthzRemoteException(response.getStatusCode().value(), path, text);
                    })
                    .body(String.class);
        } catch (RestClientException e) {
            // 网络层失败（连接/读取超时、响应体停摆）：fail-closed，由调用方决定拒绝还是降级
            if (!EisAuthzRemoteException.isNetworkFailure(e)) {
                throw e;
            }
            throw EisAuthzRemoteException.resourceFailure(path, e);
        }
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
