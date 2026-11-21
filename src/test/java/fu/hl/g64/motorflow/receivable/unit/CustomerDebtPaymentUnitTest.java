package fu.hl.g64.motorflow.receivable.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentRequest;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentResponse;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.PaymentAllocation;
import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger;
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.receivable.repository.PaymentAllocationRepository;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.receivable.service.impl.CustomerDebtServiceImpl;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Report 5.1, sheet CustomerDebt.recordPayment: CustomerDebtServiceImpl.recordPayment(collector, customerId,
 * request), UTCID01-21. Store ID = 2 "Kho Gia Lâm"; customer ID = 20 owes there debt ID = 100 (opening balance,
 * 300,000 left, oldest) and debt ID = 101 (credit sale, 200,000 left). DebtLedger and TransactionReferences are the
 * real classes over the Mockito repositories; a new payment is saved as ID = 900.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerDebtPaymentUnitTest {

    @Mock CustomerRepository customers;
    @Mock ReceivableRepository receivables;
    @Mock PaymentRepository payments;
    @Mock PaymentAllocationRepository allocations;
    @Mock StoreRepository stores;
    @Mock UserAccountRepository users;
    @Mock StoreScope storeScope;
    @Mock AttachmentService attachments;
    @Mock AuditLogRepository auditLog;
    @Mock SalesOrderRepository salesOrders;
    @Mock PlatformTransactionManager transactionManager;

    private CustomerDebtServiceImpl service;
    private final AuthenticatedUser manager = auth(5, Role.BRANCH_MANAGER, 2L);
    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private Receivable opening, credit;
    /** What debt 100 and debt 101 still owe. */
    private long owed100 = 300_000, owed101 = 200_000;
    private final List<PaymentAllocation> savedAllocations = new ArrayList<>();

    @BeforeEach
    void data() {
        service = new CustomerDebtServiceImpl(customers, receivables, payments, allocations, stores, users, storeScope,
                attachments, auditLog, new DebtLedger(receivables, salesOrders, CLOCK),
                new TransactionReferences(payments, transactionManager), CLOCK);
        Store gialam = with(store(2, StoreStatus.ACTIVE, false), "storeName", "Kho Gia Lâm");
        when(stores.findById(anyLong())).thenReturn(Optional.empty());
        when(stores.findById(2L)).thenReturn(Optional.of(gialam));
        when(stores.findById(9L)).thenReturn(Optional.of(store(9, StoreStatus.ACTIVE, false)));
        when(customers.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(customers.findByIdForUpdate(20L)).thenReturn(Optional.of(withId(Customer.create("Anh Tuấn",
                "0912345678", null, null, 1L, NOW.minusMonths(6)), 20)));
        opening = withId(Receivable.openingBalance(20, 2, 300_000, NOW.toLocalDate().minusDays(10), null, 1,
                NOW.minusMonths(2)), 100);
        credit = withId(Receivable.creditSale(20, 2, 60, 200_000, NOW.toLocalDate().plusDays(20), 5,
                NOW.minusDays(10)), 101);
        when(receivables.findWithRemaining(20L, 2L)).thenAnswer(inv -> List.of(new Object[]{credit, owed101},
                new Object[]{opening, owed100}));
        when(payments.findByTransactionReference(anyString())).thenReturn(List.of());
        when(payments.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0, Payment.class), 900));
        when(allocations.saveAll(any())).thenAnswer(inv -> {
            inv.<Iterable<PaymentAllocation>>getArgument(0).forEach(savedAllocations::add);
            return List.copyOf(savedAllocations);
        });
    }

    private static DebtPaymentRequest.AllocationLine split(long receivableId, long remainingBefore, long amount) {
        return new DebtPaymentRequest.AllocationLine(receivableId, remainingBefore, amount);
    }

    private static DebtPaymentRequest request(Long storeId, long amount, Long tendered, PaymentMethod method,
                                              String reference, List<Long> receivableIds,
                                              DebtPaymentRequest.AllocationLine... expected) {
        return new DebtPaymentRequest(storeId, amount, tendered, method, reference, receivableIds, List.of(expected),
                List.of());
    }

    /** Cash, no tendered amount, every debt eligible. */
    private static DebtPaymentRequest cash(long amount, DebtPaymentRequest.AllocationLine... expected) {
        return request(null, amount, null, PaymentMethod.CASH, null, null, expected);
    }

    private static void assertSplit(DebtPaymentResponse.Allocation line, long receivableId, long allocated,
                                    long remaining) {
        assertThat(line.receivableId()).isEqualTo(receivableId);
        assertThat(line.allocatedAmount()).isEqualTo(allocated);
        assertThat(line.remainingAmount()).isEqualTo(remaining);
    }

    private void assertRefused(AuthenticatedUser user, long customerId, DebtPaymentRequest request,
                               HttpStatus status, String code) {
        assertBusiness(() -> service.recordPayment(user, customerId, request), status, code);
        verify(payments, never()).saveAndFlush(any());
        verify(allocations, never()).saveAll(any());
    }

    @Test
    @DisplayName("UTCID01 N: 400,000 cash, 500,000 tendered -> 300,000 to debt 100 then 100,000 to debt 101")
    void utc01() {
        DebtPaymentResponse paid = service.recordPayment(manager, 20, request(null, 400_000, 500_000L,
                PaymentMethod.CASH, null, null, split(100, 300_000, 300_000), split(101, 200_000, 100_000)));

        assertThat(paid.id()).isEqualTo(900);
        assertThat(paid.status()).isEqualTo(PaymentStatus.VERIFIED);
        assertThat(paid.amount()).isEqualTo(400_000);
        assertThat(paid.tenderedAmount()).isEqualTo(500_000);
        assertThat(paid.changeAmount()).isEqualTo(100_000);
        assertThat(paid.allocations()).hasSize(2);
        assertSplit(paid.allocations().get(0), 100, 300_000, 0);
        assertSplit(paid.allocations().get(1), 101, 100_000, 100_000);
    }

    @Test
    @DisplayName("UTCID02 N: Owner, store 2, bank transfer with reference \"  FT2611180001 \"")
    void utc02() {
        DebtPaymentResponse paid = service.recordPayment(owner, 20, request(2L, 100_000, null,
                PaymentMethod.BANK_TRANSFER, "  FT2611180001 ", null, split(100, 300_000, 100_000)));

        assertThat(paid.id()).isEqualTo(900);
        assertThat(paid.amount()).isEqualTo(100_000);
        assertThat(paid.paymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(paid.transactionReference()).isEqualTo("FT2611180001");
        assertThat(paid.changeAmount()).isNull();
        assertThat(paid.allocations()).singleElement().satisfies(line -> assertSplit(line, 100, 100_000, 200_000));
    }

    @Test
    @DisplayName("UTCID03 N: only debt 101 named -> 150,000 to it")
    void utc03() {
        DebtPaymentResponse paid = service.recordPayment(manager, 20, request(null, 150_000, null,
                PaymentMethod.CASH, null, List.of(101L), split(101, 200_000, 150_000)));

        assertThat(paid.amount()).isEqualTo(150_000);
        assertThat(paid.allocations()).singleElement().satisfies(line -> assertSplit(line, 101, 150_000, 50_000));
    }

    @Test
    @DisplayName("UTCID04 B: 500,000, everything owed -> both debts settled")
    void utc04() {
        DebtPaymentResponse paid = service.recordPayment(manager, 20, cash(500_000, split(100, 300_000, 300_000),
                split(101, 200_000, 200_000)));

        assertThat(paid.amount()).isEqualTo(500_000);
        assertSplit(paid.allocations().get(0), 100, 300_000, 0);
        assertSplit(paid.allocations().get(1), 101, 200_000, 0);
        assertThat(opening.getSettledAt()).isEqualTo(NOW);
        assertThat(credit.getSettledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("UTCID05 B: tendered = amount 300,000 -> change 0, one allocation")
    void utc05() {
        DebtPaymentResponse paid = service.recordPayment(manager, 20, request(null, 300_000, 300_000L,
                PaymentMethod.CASH, null, null, split(100, 300_000, 300_000), split(101, 200_000, 0)));

        assertThat(paid.amount()).isEqualTo(300_000);
        assertThat(paid.changeAmount()).isZero();
        assertThat(paid.allocations()).singleElement().satisfies(line -> assertSplit(line, 100, 300_000, 0));
    }

    @Test
    @DisplayName("UTCID06 A: Branch Manager of store 2 names store 9 -> MSG05")
    void utc06() {
        assertRefused(manager, 20, request(9L, 100_000, null, PaymentMethod.CASH, null, null,
                split(100, 300_000, 100_000)), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID07 A: Owner names no store -> MSG155")
    void utc07() {
        assertRefused(owner, 20, cash(100_000, split(100, 300_000, 100_000)), HttpStatus.BAD_REQUEST, "MSG155");
    }

    @Test
    @DisplayName("UTCID08 A: tendered 90,000 below the amount 100,000 -> MSG153")
    void utc08() {
        assertRefused(manager, 20, request(null, 100_000, 90_000L, PaymentMethod.CASH, null, null,
                split(100, 300_000, 100_000)), HttpStatus.BAD_REQUEST, "MSG153");
    }

    @Test
    @DisplayName("UTCID09 A: tendered amount with a bank transfer -> MSG153")
    void utc09() {
        assertRefused(manager, 20, request(null, 100_000, 100_000L, PaymentMethod.BANK_TRANSFER, "FT1", null,
                split(100, 300_000, 100_000)), HttpStatus.BAD_REQUEST, "MSG153");
    }

    @Test
    @DisplayName("UTCID10 A: bank transfer with a blank reference -> MSG154")
    void utc10() {
        assertRefused(manager, 20, request(null, 100_000, null, PaymentMethod.BANK_TRANSFER, "  ", null,
                split(100, 300_000, 100_000)), HttpStatus.BAD_REQUEST, "MSG154");
    }

    @Test
    @DisplayName("UTCID11 A: customer 404 does not exist -> MSG74")
    void utc11() {
        assertRefused(manager, 404, cash(100_000, split(100, 300_000, 100_000)), HttpStatus.NOT_FOUND, "MSG74");
    }

    @Test
    @DisplayName("UTCID12 A: reference FT1 already on payment 55 -> MSG151")
    void utc12() {
        when(payments.findByTransactionReference("FT1")).thenReturn(List.of(withId(Payment.verifiedDebtPayment(
                50_000, null, PaymentMethod.BANK_TRANSFER, "FT1", 5, NOW.minusDays(1)), 55)));

        assertRefused(manager, 20, request(null, 100_000, null, PaymentMethod.BANK_TRANSFER, "FT1", null,
                split(100, 300_000, 100_000)), HttpStatus.CONFLICT, "MSG151");
    }

    @Test
    @DisplayName("UTCID13 A: the customer owes nothing at store 2 -> MSG149")
    void utc13() {
        owed100 = 0;
        owed101 = 0;

        assertRefused(manager, 20, cash(100_000, split(100, 0, 100_000)), HttpStatus.CONFLICT, "MSG149");
    }

    @Test
    @DisplayName("UTCID14 A: debt 999 is not owed -> MSG148")
    void utc14() {
        assertRefused(manager, 20, request(null, 100_000, null, PaymentMethod.CASH, null, List.of(999L),
                split(999, 100_000, 100_000)), HttpStatus.BAD_REQUEST, "MSG148");
    }

    @Test
    @DisplayName("UTCID15 A: 500,001, above the 500,000 owed -> MSG150")
    void utc15() {
        assertRefused(manager, 20, cash(500_001, split(100, 300_000, 300_000), split(101, 200_000, 200_001)),
                HttpStatus.BAD_REQUEST, "MSG150");
    }

    @Test
    @DisplayName("UTCID16 A: debt 100 now owes 250,000, changed since the preview -> MSG152")
    void utc16() {
        owed100 = 250_000;

        assertRefused(manager, 20, cash(250_000, split(100, 300_000, 250_000)), HttpStatus.CONFLICT, "MSG152");
    }

    @Test
    @DisplayName("UTCID17 N: Branch Manager names their own store 2 -> accepted")
    void utc17() {
        DebtPaymentResponse paid = service.recordPayment(manager, 20, request(2L, 100_000, null, PaymentMethod.CASH,
                null, null, split(100, 300_000, 100_000)));

        assertThat(paid.id()).isEqualTo(900);
        assertThat(paid.storeId()).isEqualTo(2);
        assertThat(paid.amount()).isEqualTo(100_000);
        assertThat(paid.allocations()).singleElement().satisfies(line -> assertSplit(line, 100, 100_000, 200_000));
    }

    @Test
    @DisplayName("UTCID18 B: receivableIds = [] -> every debt still owed is eligible, oldest first")
    void utc18() {
        DebtPaymentResponse paid = service.recordPayment(manager, 20, request(null, 350_000, null,
                PaymentMethod.CASH, null, List.of(), split(100, 300_000, 300_000), split(101, 200_000, 50_000)));

        assertThat(paid.amount()).isEqualTo(350_000);
        assertSplit(paid.allocations().get(0), 100, 300_000, 0);
        assertSplit(paid.allocations().get(1), 101, 50_000, 150_000);
    }

    @Test
    @DisplayName("UTCID19 A: debt 100 twice in the confirmed allocation -> MSG152")
    void utc19() {
        assertRefused(manager, 20, cash(300_000, split(100, 300_000, 300_000), split(100, 300_000, 0)),
                HttpStatus.CONFLICT, "MSG152");
    }

    @Test
    @DisplayName("UTCID20 A: confirmed 100,000 + 100,000 differs from the oldest-first 200,000 + 0 -> MSG152")
    void utc20() {
        assertRefused(manager, 20, cash(200_000, split(100, 300_000, 100_000), split(101, 200_000, 100_000)),
                HttpStatus.CONFLICT, "MSG152");
    }

    @Test
    @DisplayName("UTCID21 A: Owner names store 99, which does not exist -> MSG52")
    void utc21() {
        assertRefused(owner, 20, request(99L, 100_000, null, PaymentMethod.CASH, null, null,
                split(100, 300_000, 100_000)), HttpStatus.NOT_FOUND, "MSG52");
    }
}
