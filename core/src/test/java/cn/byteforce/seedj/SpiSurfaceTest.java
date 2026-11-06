package cn.byteforce.seedj;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import cn.byteforce.seedj.pki.PkiProvider;
import cn.byteforce.seedj.transit.TransitProvider;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包级"公开表面"守卫：每个能力的<b>公开</b>顶层类型是白名单，新增类型必须显式登记。
 *
 * <p>这是 ADR-0001 的教训：机制类型（状态存储、文件锁、实现细节）一旦漏进
 * 契约包，所有实现都被迫接受它。三个能力共用同一张检查表。
 * 包私有类型（如 {@code Validation}）不参与检查。
 */
class SpiSurfaceTest {

    /** 用主代码里的类型定位 {@code target/classes}，避免误扫到测试类目录。 */
    private static final Map<String, Class<?>> MARKERS = Map.of(
            "cn.byteforce.seedj.pki", PkiProvider.class,
            "cn.byteforce.seedj.transit", TransitProvider.class,
            "cn.byteforce.seedj.eventbus", EventPublisher.class,
            "cn.byteforce.seedj.eventbus.spi", cn.byteforce.seedj.eventbus.spi.EventBusTransport.class,
            "cn.byteforce.seedj.outbox", OutboxWriter.class,
            "cn.byteforce.seedj.outbox.spi", OutboxStore.class,
            "cn.byteforce.seedj.id", IdGenerator.class,
            "cn.byteforce.seedj.core", ErrorCategory.class,
            "cn.byteforce.seedj.workflow.spi", WorkflowProvider.class
    );

    /**
     * 后续能力单独登记：{@code Map.of} 最多 10 组键值，新能力不再挤进上面的表。
     */
    private static final Map<String, Class<?>> EXTRA_MARKERS = Map.of(
            "cn.byteforce.seedj.cqrs", cn.byteforce.seedj.cqrs.IdCodec.class,
            "cn.byteforce.seedj.cqrs.spi", cn.byteforce.seedj.cqrs.spi.EventStore.class,
            "cn.byteforce.seedj.lock", cn.byteforce.seedj.lock.LockProvider.class,
            "cn.byteforce.seedj.core.security", CallerProvider.class
    );

    private static final Map<String, List<String>> EXTRA_ALLOWED = Map.of(
            "cn.byteforce.seedj.core.security",
            List.of("AccessTokenRules", "Caller", "CallerProvider", "SecurityAuditEvent",
                    "SecurityAuditSink", "SecurityErrorCode", "TokenClaims", "DerivedField",
                    "DerivedFieldKeyRing"),

            "cn.byteforce.seedj.cqrs",
            List.of("AbstractProjection", "Command", "CommandAgent", "CommandAudit",
                    "CommandCatalog", "CommandInterceptor", "CommandReceipt", "CommandRecord",
                    "CommandInvocation", "CommandRuntime", "CommandStatus", "CommandSubmission",
                    "CqrsErrorCode", "CqrsException", "EventSourcedRepository", "EventStream",
                    "IdCodec", "IdCodecs", "IdempotencyFingerprint", "Query", "QueryAgent",
                    "QueryInterceptor", "QueryInvocation", "StreamId",
                    "TemporalCommandAgent", "TemporalCommandAudit", "CommandPayloadRedactor"),

            "cn.byteforce.seedj.cqrs.spi",
            List.of("CommandStore", "EventStore", "TransactionRunner"),

            "cn.byteforce.seedj.lock",
            List.of("LockErrorCode", "LockException", "LockLease", "LockProvider")
    );

    private static final Map<String, List<String>> ALLOWED = Map.of(
            "cn.byteforce.seedj.pki",
            List.of("PkiErrorCode", "PkiException", "PkiProvider"),

            "cn.byteforce.seedj.transit",
            List.of("BatchDecryptRequest", "BatchEncryptRequest", "BatchItem", "BatchResult",
                    "BatchRewrapRequest", "Ciphertext", "CreateKeyRequest", "DataKey",
                    "DataKeyRequest", "DecryptRequest", "EncryptRequest", "ExportedKey",
                    "HmacAlgorithm", "HmacRequest", "KeyConfigRequest", "KeyInfo", "KeyType",
                    "MacResult", "RewrapRequest", "SignatureAlgorithm", "Signature", "SignRequest",
                    "TransitCrypto", "TransitErrorCode", "TransitException", "TransitKeyAdmin",
                    "TransitMac", "TransitOperation", "TransitProvider", "TransitSigner",
                    "VerifyHmacRequest", "VerifyRequest"),

            "cn.byteforce.seedj.eventbus",
            List.of("EventBusErrorCode", "EventBusException", "EventEnvelope", "EventMessage",
                    "EventPublisher", "EventSubscriber", "EventSubscription"),

            "cn.byteforce.seedj.eventbus.spi",
            List.of("DeadLetter", "DeliveryFailure", "EventBusTransport", "EventConsumerStore",
                    "EventPayloadSerializer", "PollRequest", "ReceivedEvent"),

            "cn.byteforce.seedj.outbox",
            List.of("OutboxErrorCode", "OutboxException", "OutboxRecord", "OutboxWriter"),

            "cn.byteforce.seedj.outbox.spi",
            List.of("ClaimRequest", "ClaimToken", "ClaimedRecord", "OutboxFailure",
                    "OutboxSink", "OutboxStore", "InboxRecord", "InboxStatus", "InboxStore"),

            "cn.byteforce.seedj.id",
            List.of("IdErrorCode", "IdException", "IdGenerator"),

            "cn.byteforce.seedj.core",
            List.of("CodeEnum", "CodeEnums", "ErrorCategory", "ErrorDescriptor",
                    "InfrastructureCapability", "InfrastructureErrorCode",
                    "InfrastructureException", "Migration", "MigrationChecksums"),

            "cn.byteforce.seedj.workflow.spi",
            List.of("ActionDecision", "ActionRequest", "AddAssigneeAction",
                    "AddAssigneePosition", "AllApprove", "AnyApprove",
                    "AnyRejectTerminates", "ApproveAction", "ApproveRecord",
                    "AssigneeNotAllowedException", "AssignmentStatus", "AtLeastCount",
                    "AtLeastPercentage", "AtLeastRejects", "CancelWorkflowRequest",
                    "CcAction", "DelegateAction", "HumanTask", "HumanTaskKind",
                    "HumanTaskNotFoundException", "HumanTaskSpec",
                    "HumanTaskStateConflictException", "HumanTaskStatus",
                    "ParticipantResolutionContext", "ParticipantResolutionException",
                    "ParticipantSpec", "ParticipantType", "RecordAndContinue",
                    "RejectAction", "RejectCompletion", "RejectTargetNotAllowedException",
                    "RemoveAssigneeAction", "ResumeWorkflowAction", "RetryPolicy",
                    "SignalAction", "SignCompletion", "StartWorkflowRequest",
                    "StepDefinition", "StepKind", "TaskAssignment", "TaskId", "TaskQuery",
                    "TerminateWorkflowRequest", "TransferAction", "UrgeAction",
                    "WithdrawAction", "WithdrawInstanceAction",
                    "WithdrawNotAllowedException", "WorkflowAction", "WorkflowCapabilities",
                    "WorkflowDefinition", "WorkflowDefinitionId",
                    "WorkflowDefinitionNotFoundException", "WorkflowDefinitionVersion",
                    "WorkflowException", "WorkflowFailure", "WorkflowHistoryEvent",
                    "WorkflowInstance", "WorkflowInstanceId", "WorkflowNotFoundException",
                    "WorkflowProvider", "WorkflowProviderException",
                    "WorkflowSearchQuery", "WorkflowStateConflictException",
                    "WorkflowStatus", "WorkflowTimeoutException",
                    "WorkflowVersionConflictException")
    );

    @ParameterizedTest
    @ValueSource(strings = {
            "cn.byteforce.seedj.pki",
            "cn.byteforce.seedj.transit",
            "cn.byteforce.seedj.eventbus",
            "cn.byteforce.seedj.eventbus.spi",
            "cn.byteforce.seedj.outbox",
            "cn.byteforce.seedj.outbox.spi",
            "cn.byteforce.seedj.id",
            "cn.byteforce.seedj.core",
            "cn.byteforce.seedj.workflow.spi",
            "cn.byteforce.seedj.cqrs",
            "cn.byteforce.seedj.cqrs.spi",
            "cn.byteforce.seedj.lock",
            "cn.byteforce.seedj.core.security"
    })
    void packageExposesOnlyWhitelistedPublicTypes(String packageName) throws Exception {
        Class<?> marker = markerOf(packageName);
        Path dir = Path.of(marker.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(packageName.replace('.', '/'));

        List<String> publicTypes;
        try (Stream<Path> files = Files.list(dir)) {
            publicTypes = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.contains("$"))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(name -> isPublic(packageName + "." + name))
                    .sorted()
                    .toList();
        }

        assertThat(publicTypes)
                .as("public surface of %s", packageName)
                .containsExactlyInAnyOrderElementsOf(allowedTypes(packageName));
    }

    private static Class<?> markerOf(String packageName) {
        Class<?> marker = MARKERS.get(packageName);
        return marker != null ? marker : EXTRA_MARKERS.get(packageName);
    }

    private static List<String> allowedTypes(String packageName) {
        List<String> allowed = ALLOWED.get(packageName);
        return allowed != null ? allowed : EXTRA_ALLOWED.get(packageName);
    }

    private static boolean isPublic(String className) {
        try {
            return Modifier.isPublic(Class.forName(className).getModifiers());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
