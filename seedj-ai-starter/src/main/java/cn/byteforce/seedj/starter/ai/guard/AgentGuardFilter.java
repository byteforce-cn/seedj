package cn.byteforce.seedj.starter.ai.guard;

import cn.byteforce.seedj.ai.AiErrorCode;
import cn.byteforce.seedj.ai.GuardVerdict;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenVerifier;
import cn.byteforce.seedj.starter.ai.token.GuardContext;
import cn.byteforce.seedj.starter.cqrs.web.CommandRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 执行侧强制门禁：没有合法 DecisionToken 就不许提交写命令（spec §7.3 的十条校验，全部 fail-closed）。
 *
 * <p><b>拦截面</b>：只拦单段 {@code POST /commands/{commandType}}。{@code GET /commands/{id}}、{@code
 * /queries/**}、{@code /actuator/**}、{@code /.well-known/**} 一律放行——读路径不产生副作用，给它们要 token
 * 只会逼调用方伪造凭据。判定写在 {@link #shouldNotFilter(HttpServletRequest)} 里，{@code doFilterInternal} 不再
 * 重复判断。
 *
 * <p><b>装配顺序</b>：安全链（{@code springSecurityFilterChain}，order = -100）先把 bearer 认成 {@link Caller}，
 * 本过滤器（order = -90）再消费 {@link CallerProvider} 与 {@code x-agent-decision}；servlet filter 一律早于
 * {@code DispatcherServlet}，所以仍然在命令入口 {@code CommandController} 之前。顺序反了（跑在安全链之前）会让
 * {@code CallerProvider} 恒为空、每个写命令都 401。
 *
 * <p><b>身份口径</b>：{@code actor} 由 {@link CallerIdCodec} 解析、{@code tenant} 是<b>不透明字符串</b>（与 token 的
 * {@code tenant} claim 逐字比较）。两者都必须与 {@code CommandController} 用同一套策略，否则 guard 与命令入口会算出
 * 不同身份、每个写命令都 403。
 *
 * <p><b>原始请求体</b>：{@code args_hash} 按请求体<b>未脱敏原文</b>重算——业务侧的脱敏只影响落库与可读性，不影响
 * 可比性，所以这里不能用任何脱敏后的对象。读到的字节还会交给下游：本过滤器向链上传一个可重放的请求包装，
 * {@code @RequestBody} 拿到的仍是完整原文。
 *
 * <p><b>版本口径</b>：{@code expectedVersion} 必须与 {@code CommandController} 的解析优先级<b>完全一致</b>（{@code
 * If-Match} 优先于 body，两者都给且不等则 400）。否则 token 钉死的版本可被绕过：只带 {@code If-Match} 就能换版本，
 * 或干脆两边都不带让版本守卫失效。
 *
 * <p><b>错误响应</b>：缺凭据 401（{@code WWW-Authenticate: AgentDecision}），token 校验不过 403，坏载荷 400；body
 * 统一 {@code {"code": ..., "detail": ...}}。这里的 {@code token_missing}、{@code unauthenticated}、{@code
 * invalid_payload} 是<b>过滤器层</b>的 wire 字面量，与 {@link AiErrorCode} 不是同一套——后者只描述 token 校验结论。
 * token 原文不进日志、不进响应，拒绝说明只带 {@code kid} 与 {@code decision_id} 前 8 位（由校验器给出）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AgentGuardFilter extends OncePerRequestFilter {

    /** 决策 token 的传输头；gRPC 侧用同名 metadata。 */
    public static final String DECISION_HEADER = "x-agent-decision";

    /** 401 的质询方案名：客户端应据此取决策 token。 */
    public static final String CHALLENGE_SCHEME = "AgentDecision";

    /** 请求体上限；命令载荷是结构化小对象，超过 1 MiB 一律按坏载荷拒绝。 */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final String WIRE_TOKEN_MISSING = "token_missing";

    private static final String WIRE_UNAUTHENTICATED = "unauthenticated";

    private static final String WIRE_INVALID_PAYLOAD = "invalid_payload";

    private static final String COMMANDS_PREFIX = "/commands/";

    private static final int CHUNK_BYTES = 8192;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final DecisionTokenVerifier verifier;

    private final CallerProvider callers;

    private final CallerIdCodec callerIds;

    private final String audience;

    private final Clock clock;

    /**
     * 构造过滤器。
     *
     * @param verifier token 校验器：十条校验的唯一实现
     * @param callers 当前调用方；由 biz 安全链填充
     * @param callerIds 调用方标识 → actor 的编解码：必须与 {@code CommandController} 用同一个策略
     * @param audience 本服务名；{@code null} 或空白表示用请求的 server name
     * @param clock 取时入口，用于 token 有效期判定
     * @throws NullPointerException 除 {@code audience} 外任一参数为 {@code null}
     */
    public AgentGuardFilter(
            DecisionTokenVerifier verifier,
            CallerProvider callers,
            CallerIdCodec callerIds,
            String audience,
            Clock clock) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.callerIds = Objects.requireNonNull(callerIds, "callerIds");
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || commandTypeInPath(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Caller caller = callers.find().orElse(null);
        if (caller == null) {
            reject(response, WIRE_UNAUTHENTICATED, "authentication required", true);
            return;
        }
        UUID actor;
        try {
            // actor 的取法必须与 CommandController 完全一致：换 codec（uuid / nameUuid）不能只换一边。
            actor = callerIds.toActor(caller);
        } catch (ContractException unauthenticated) {
            reject(
                    response,
                    WIRE_UNAUTHENTICATED,
                    "caller id cannot be resolved to an actor",
                    true);
            return;
        }
        // 租户是不透明字符串：token 的 tenant claim 是同一形态（例如 "t_07"），逐字比较，不做 UUID 归一。
        String tenantId = caller.tenantId();

        String token = request.getHeader(DECISION_HEADER);
        if (token == null || token.isBlank()) {
            reject(response, WIRE_TOKEN_MISSING, DECISION_HEADER + " is required", true);
            return;
        }

        Optional<byte[]> rawBody;
        try {
            rawBody = readBody(request);
        } catch (IOException unreadable) {
            reject(response, WIRE_INVALID_PAYLOAD, "request body cannot be read", false);
            return;
        }
        if (rawBody.isEmpty()) {
            reject(
                    response,
                    WIRE_INVALID_PAYLOAD,
                    "request body must be at most " + MAX_BODY_BYTES + " bytes",
                    false);
            return;
        }
        byte[] body = rawBody.get();

        CommandRequest command;
        try {
            command = MAPPER.readValue(body, CommandRequest.class);
        } catch (JacksonException notJson) {
            reject(response, WIRE_INVALID_PAYLOAD, "command request body is not valid JSON", false);
            return;
        }
        String problem = problemWith(command);
        if (problem != null) {
            reject(response, WIRE_INVALID_PAYLOAD, problem, false);
            return;
        }
        Long expectedVersion;
        try {
            expectedVersion =
                    effectiveExpectedVersion(
                            command.expectedVersion(), request.getHeader(HttpHeaders.IF_MATCH));
        } catch (IllegalArgumentException badVersion) {
            reject(response, WIRE_INVALID_PAYLOAD, badVersion.getMessage(), false);
            return;
        }

        GuardContext context;
        try {
            context =
                    new GuardContext(
                            actor,
                            tenantId,
                            command.type(),
                            command.payload(),
                            command.targets(),
                            expectedVersion,
                            command.idempotencyKey(),
                            audienceOf(request));
        } catch (RuntimeException invalidRequest) {
            // GuardContext 的校验（含 List.copyOf 对 null 元素的拒绝）都是坏请求，不能漏成 500。
            reject(response, WIRE_INVALID_PAYLOAD, "invalid command request", false);
            return;
        }

        GuardVerdict verdict = verifier.verify(token, context, clock.now());
        if (verdict instanceof GuardVerdict.Reject rejected) {
            reject(response, rejected.code().wire(), rejected.detail(), false);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** 命令载荷的必填项；返回 {@code null} 表示可以继续校验。 */
    private static String problemWith(CommandRequest command) {
        if (command == null) {
            return "command request body is required";
        }
        if (command.type() == null || command.type().isBlank()) {
            return "type is required";
        }
        if (command.payload() == null) {
            return "payload is required";
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return "idempotency_key is required";
        }
        if (command.targets() != null && command.targets().contains(null)) {
            return "targets must not contain null";
        }
        return null;
    }

    /**
     * 生效的期望版本：与 {@code CommandController#expectedVersion} 同一套优先级。
     *
     * @throws IllegalArgumentException {@code If-Match} 无法解析、为负数，或与 body 声明不一致
     */
    private static Long effectiveExpectedVersion(Long bodyVersion, String ifMatch) {
        Long headerVersion = parseIfMatch(ifMatch);
        if (headerVersion != null && bodyVersion != null && !headerVersion.equals(bodyVersion)) {
            throw new IllegalArgumentException("If-Match and expected_version disagree");
        }
        return headerVersion == null ? bodyVersion : headerVersion;
    }

    private static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long version = Long.parseLong(value);
            if (version < 0) {
                throw new NumberFormatException("negative");
            }
            return version;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid If-Match value: " + ifMatch);
        }
    }

    /** 受众：配置优先，未配置时取请求到了哪个服务；都拿不到时用不匹配 token 的占位值（fail-closed）。 */
    private String audienceOf(HttpServletRequest request) {
        if (audience != null && !audience.isBlank()) {
            return audience;
        }
        String serverName = request.getServerName();
        return serverName == null || serverName.isBlank() ? "unknown" : serverName;
    }

    /**
     * 读原始请求体；超过 {@link #MAX_BODY_BYTES} 返回空。
     *
     * <p>用 {@link ContentCachingRequestWrapper} 读：缓存上限与 {@code MAX_BODY_BYTES} 一致，读超上限即停，不会
     * 因为超大 body 撑爆内存。读到的字节由 {@link CachedBodyRequest} 交给下游重放——缓存包装本身只缓存不重放，
     * 读完再把原始请求传下去，{@code @RequestBody} 会拿到空 body。
     */
    private static Optional<byte[]> readBody(HttpServletRequest request) throws IOException {
        ServletInputStream input =
                new ContentCachingRequestWrapper(request, MAX_BODY_BYTES).getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK_BYTES];
        int read = input.read(chunk);
        while (read != -1) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_BODY_BYTES) {
                return Optional.empty();
            }
            read = input.read(chunk);
        }
        return Optional.of(buffer.toByteArray());
    }

    private static void reject(
            HttpServletResponse response, String code, String detail, boolean challenge)
            throws IOException {
        response.setStatus(challenge ? HttpServletResponse.SC_UNAUTHORIZED : statusFor(code));
        if (challenge) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_SCHEME);
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        MAPPER.writeValue(
                response.getOutputStream(),
                Map.of("code", code, "detail", Objects.requireNonNullElse(detail, "")));
    }

    /** 坏载荷是 400，其余拒绝一律 403（token 校验不过）。 */
    private static int statusFor(String code) {
        return WIRE_INVALID_PAYLOAD.equals(code)
                ? HttpServletResponse.SC_BAD_REQUEST
                : HttpServletResponse.SC_FORBIDDEN;
    }

    /** 单段 {@code /commands/{commandType}} 里的命令类型；其余路径返回 {@code null}。 */
    private static String commandTypeInPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isEmpty()) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!path.startsWith(COMMANDS_PREFIX)) {
            return null;
        }
        String commandType = path.substring(COMMANDS_PREFIX.length());
        return commandType.isEmpty() || commandType.indexOf('/') >= 0 ? null : commandType;
    }

    /** 可重放请求体：下游按需重复读取同一份原文。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            return encoding == null || encoding.isBlank()
                    ? StandardCharsets.UTF_8
                    : Charset.forName(encoding);
        }
    }

    /** 同步流：每次实例化都从头读同一份字节。 */
    private static final class CachedBodyInputStream extends ServletInputStream {

        private final byte[] body;

        private int position;

        private CachedBodyInputStream(byte[] body) {
            this.body = body;
        }

        @Override
        public int read() {
            return position >= body.length ? -1 : body[position++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (position >= body.length) {
                return -1;
            }
            int available = Math.min(length, body.length - position);
            System.arraycopy(body, position, target, offset, available);
            position += available;
            return available;
        }

        @Override
        public boolean isFinished() {
            return position >= body.length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("guard 的请求体是同步流，不支持异步读");
        }
    }
}
