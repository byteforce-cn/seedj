package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令受理结果：终态字段与状态一一对应，映射自持久化记录。
 */
class CommandReceiptTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ISSUER = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final UUID LOCATOR = UUID.fromString("018f2e3a-1234-7000-8000-000000000003");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final String FINGERPRINT = "0".repeat(64);

    private static CommandRecord pending() {
        return CommandRecord.pending(ID, "hr.employee.onboard", NOW, ISSUER, Map.of(),
                "key-1", FINGERPRINT, NOW);
    }

    @Test
    @DisplayName("pending 受理结果不带终态字段")
    void pendingReceiptHasNoTerminalFields() {
        CommandReceipt receipt = CommandReceipt.from(pending());

        assertThat(receipt.commandId()).isEqualTo(ID);
        assertThat(receipt.status()).isEqualTo(CommandStatus.PENDING);
        assertThat(receipt.eventIds()).isEmpty();
        assertThat(receipt.locator()).isNull();
        assertThat(receipt.version()).isNull();
    }

    @Test
    @DisplayName("accepted 受理结果携带 locator、version 与事件 id")
    void acceptedReceiptCarriesLocator() {
        UUID eventId = UUID.fromString("018f2e3a-1234-7000-8000-000000000004");
        CommandReceipt receipt = CommandReceipt.from(
                pending().accepted(LOCATOR, 3L, List.of(eventId)));

        assertThat(receipt.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(receipt.locator()).isEqualTo(LOCATOR);
        assertThat(receipt.version()).isEqualTo(3L);
        assertThat(receipt.eventIds()).containsExactly(eventId);
    }

    @Test
    @DisplayName("accepted 回执可以只带 target_ref")
    void acceptedReceiptCarriesTargetRef() {
        CommandReceipt receipt = CommandReceipt.from(pending().accepted("emp-1", 1L, List.of()));

        assertThat(receipt.locator()).isNull();
        assertThat(receipt.targetRef()).isEqualTo("emp-1");
        assertThat(receipt.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("accepted 回执要求 locator 或 target_ref 恰好一个")
    void acceptedReceiptRequiresExactlyOneTarget() {
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.ACCEPTED, List.of(),
                null, null, 1L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("rejected 受理结果携带冻结错误码与原因")
    void rejectedReceiptCarriesCode() {
        CommandReceipt receipt = CommandReceipt.from(
                pending().rejected(ContractErrorCode.FORBIDDEN, "no permission"));

        assertThat(receipt.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(receipt.code()).isEqualTo(ContractErrorCode.FORBIDDEN.getCode());
        assertThat(receipt.reason()).isEqualTo("no permission");
        assertThat(receipt.locator()).isNull();
    }

    @Test
    @DisplayName("状态与字段不匹配时构造期失败")
    void receiptGuards() {
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.PENDING, null, LOCATOR,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending receipt must not carry terminal fields");
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.ACCEPTED, null, null, 1L,
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of locator or target_ref");
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.ACCEPTED, null, LOCATOR, 0L,
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of locator or target_ref");
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.ACCEPTED, null, LOCATOR, 1L,
                "EIS-400-001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry rejection fields");
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.REJECTED, null, LOCATOR,
                null, "EIS-400-001", "why"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry locator or version");
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.REJECTED, null, null, null,
                null, "why"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires code and reason");
        assertThatThrownBy(() -> new CommandReceipt(ID, CommandStatus.REJECTED, null, null, null,
                "EIS-400-001", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires code and reason");
        assertThatThrownBy(() -> new CommandReceipt(null, CommandStatus.PENDING, List.of(), null,
                null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CommandReceipt.from(null))
                .isInstanceOf(NullPointerException.class);
    }
}
