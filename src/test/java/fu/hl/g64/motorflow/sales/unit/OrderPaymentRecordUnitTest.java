package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.sales.dto.OrderPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;

import java.util.List;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Report 5.1, sheet OrderPayment.recordPayment: SalesOrderSettlementServiceImpl.recordPayment(seller, orderId,
 * request), UTCID01-23.
 */
class OrderPaymentRecordUnitTest extends SettlementUnitTestBase {

    private static OrderPaymentRequest cash(long amount, Long tendered, long expectedPayable) {
        return new OrderPaymentRequest(amount, tendered, PaymentMethod.CASH, null, true, expectedPayable, List.of());
    }

    private static OrderPaymentRequest transfer(long amount, Long tendered, String reference, boolean verified) {
        return new OrderPaymentRequest(amount, tendered, PaymentMethod.BANK_TRANSFER, reference, verified, 250_000L,
                List.of());
    }

    private SettlementResult record(AuthenticatedUser seller, OrderPaymentRequest request) {
        SettlementResult result = service.recordPayment(seller, 10, request);
        assertThat(result.paymentId()).isEqualTo(700);
        return result;
    }

    private void assertRefused(AuthenticatedUser seller, long orderId, OrderPaymentRequest request, HttpStatus status,
                               String code) {
        assertBusiness(() -> service.recordPayment(seller, orderId, request), status, code);
        verify(payments, never()).saveAndFlush(any());
        verify(inventory, never()).takeOutAll(anyList());
    }

    private void assertRefused(OrderPaymentRequest request, HttpStatus status, String code) {
        assertRefused(manager, 10, request, status, code);
    }

    @Test
    @DisplayName("UTCID01 N: 250,000 cash, 300,000 tendered -> paid in full, COMPLETED")
    void utc01() {
        SettlementResult result = record(manager, cash(250_000, 300_000L, 250_000));

        assertThat(result.completed()).isTrue();
        assertThat(result.completionBlockedCode()).isNull();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("UTCID02 N: 100,000 of 250,000 -> payment kept, still CONFIRMED")
    void utc02() {
        SettlementResult result = record(manager, cash(100_000, null, 250_000));

        assertThat(result.completed()).isFalse();
        assertThat(result.completionBlockedCode()).isNull();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("UTCID03 N: Owner, bank transfer \" FT2611180002 \" not verified yet -> not completed")
    void utc03() {
        SettlementResult result = record(owner, transfer(250_000, null, " FT2611180002 ", false));

        assertThat(result.completed()).isFalse();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CONFIRMED);
        Payment row = paid.getFirst();
        assertThat(row.getTransactionReference()).isEqualTo("FT2611180002");
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.UNVERIFIED);
    }

    @Test
    @DisplayName("UTCID04 B: tendered = amount 250,000 -> COMPLETED")
    void utc04() {
        SettlementResult result = record(manager, cash(250_000, 250_000L, 250_000));

        assertThat(result.completed()).isTrue();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("UTCID05 B: 1 đồng -> not completed")
    void utc05() {
        SettlementResult result = record(manager, cash(1, null, 250_000));

        assertThat(result.completed()).isFalse();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("UTCID06 A: order 404 does not exist -> MSG156")
    void utc06() {
        assertRefused(manager, 404, cash(1, null, 1), HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID07 A: Branch Manager 6 of store 9 -> MSG05")
    void utc07() {
        assertRefused(auth(6, Role.BRANCH_MANAGER, 9L), 10, cash(250_000, null, 250_000), HttpStatus.FORBIDDEN,
                "MSG05");
    }

    @Test
    @DisplayName("UTCID08 A: order 10 is COMPLETED -> MSG164")
    void utc08() {
        status(SalesOrderStatus.COMPLETED);

        assertRefused(cash(1, null, 1), HttpStatus.CONFLICT, "MSG164");
    }

    @Test
    @DisplayName("UTCID09 A: order 10 is PICKED, not confirmed -> MSG179")
    void utc09() {
        status(SalesOrderStatus.PICKED);

        assertRefused(cash(1, null, 250_000), HttpStatus.CONFLICT, "MSG179");
    }

    @Test
    @DisplayName("UTCID10 A: 250,000 already verified -> MSG180")
    void utc10() {
        collected(55, 250_000, true);

        assertRefused(cash(1, null, 0), HttpStatus.CONFLICT, "MSG180");
    }

    @Test
    @DisplayName("UTCID11 A: 100,000 verified since, payable now 150,000 -> MSG194")
    void utc11() {
        collected(55, 100_000, true);

        assertRefused(cash(100_000, null, 250_000), HttpStatus.CONFLICT, "MSG194");
    }

    @Test
    @DisplayName("UTCID12 A: 260,000, above the 250,000 payable -> MSG181")
    void utc12() {
        assertRefused(cash(260_000, null, 250_000), HttpStatus.BAD_REQUEST, "MSG181");
    }

    @Test
    @DisplayName("UTCID13 A: tendered 200,000 below the amount -> MSG182")
    void utc13() {
        assertRefused(cash(250_000, 200_000L, 250_000), HttpStatus.BAD_REQUEST, "MSG182");
    }

    @Test
    @DisplayName("UTCID14 A: bank transfer without a reference -> MSG154")
    void utc14() {
        assertRefused(transfer(250_000, null, null, true), HttpStatus.BAD_REQUEST, "MSG154");
    }

    @Test
    @DisplayName("UTCID15 A: reference FT1 already on payment 55 -> MSG151")
    void utc15() {
        paid.add(withId(Payment.verifiedDebtPayment(50_000, null, PaymentMethod.BANK_TRANSFER, "FT1", 5,
                NOW.minusDays(1)), 55));

        assertRefused(transfer(250_000, null, "FT1", true), HttpStatus.CONFLICT, "MSG151");
    }

    @Test
    @DisplayName("UTCID16 A: only 1 oil sellable -> payment kept, completion blocked by MSG171")
    void utc16() {
        sellable.put(OIL, 1);

        SettlementResult result = record(manager, cash(250_000, null, 250_000));

        assertThat(result.completed()).isFalse();
        assertThat(result.completionBlockedCode()).isEqualTo("MSG171");
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CONFIRMED);
        verify(inventory, never()).takeOutAll(anyList());
    }

    @Test
    @DisplayName("UTCID17 A: 300,000 tendered with a bank transfer -> MSG182")
    void utc17() {
        assertRefused(transfer(250_000, 300_000L, "FT2611180003", true), HttpStatus.BAD_REQUEST, "MSG182");
    }

    @Test
    @DisplayName("UTCID18 N: 100,000 cash with a photo of the receipt -> attached to payment 700")
    void utc18() {
        List<AttachmentUpload> photo = List.of(new AttachmentUpload("pay/bien-lai.jpg", "bien-lai.jpg",
                "image/jpeg", 1000L));

        SettlementResult result = record(manager, new OrderPaymentRequest(100_000L, null, PaymentMethod.CASH, null,
                true, 250_000L, photo));

        assertThat(result.completed()).isFalse();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CONFIRMED);
        verify(attachments).attach(OwnerType.PAYMENT, 700L, photo, 5L);
    }

    @Test
    @DisplayName("UTCID19 B: the order is cancelled by someone else right after the payment -> kept, not completed")
    void utc19() {
        int[] calls = {0};
        doAnswer(inv -> {
            if (calls[0]++ == 1) {
                status(SalesOrderStatus.CANCELLED);
            }
            return inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null);
        }).when(transactions).execute(any());

        SettlementResult result = record(manager, cash(250_000, null, 250_000));

        assertThat(result.completed()).isFalse();
        assertThat(result.completionBlockedCode()).isNull();
        assertThat(paid).hasSize(1);
    }

    @Test
    @DisplayName("UTCID20 A: taking the goods out fails with a lock timeout -> kept, MSG195 to retry")
    void utc20() {
        when(inventory.takeOutAll(anyList())).thenThrow(new PessimisticLockingFailureException("lock timeout"));

        SettlementResult result = record(manager, cash(250_000, null, 250_000));

        assertThat(result.completed()).isFalse();
        assertThat(result.completionBlockedCode()).isEqualTo("MSG195");
        assertThat(paid).hasSize(1);
    }

    @Test
    @DisplayName("UTCID21 A: order 10 is still a draft -> MSG156")
    void utc21() {
        draft();

        assertRefused(cash(1, null, 250_000), HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID22 A: Staff 7 of store 2 is not a seller -> MSG05")
    void utc22() {
        assertRefused(auth(7, Role.STAFF, 2L), 10, cash(1, null, 250_000), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID23 B: no photo list sent -> saved, nothing attached")
    void utc23() {
        SettlementResult result = record(manager, new OrderPaymentRequest(100_000L, null, PaymentMethod.CASH, null,
                true, 250_000L, null));

        assertThat(result.completed()).isFalse();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CONFIRMED);
        verify(attachments, never()).attach(any(), anyLong(), any(), anyLong());
    }
}
