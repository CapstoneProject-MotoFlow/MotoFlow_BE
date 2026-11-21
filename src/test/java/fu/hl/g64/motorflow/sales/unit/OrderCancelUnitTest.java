package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.sales.dto.CancelSalesOrderRequest;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Report 5.1, sheet OrderPayment.cancel: SalesOrderSettlementServiceImpl.cancel(seller, orderId, request), UTCID01-16.
 * Order ID = 11 of store 2 is AWAITING_PICKING with nothing collected; a refund is saved as payment ID = 701. The
 * returned SalesOrderDetail comes from SalesOrderService.get; the values the sheet lists are read from the order and
 * its payments as this call left them.
 */
class OrderCancelUnitTest extends SettlementUnitTestBase {

    private static final String REASON = "  Khách đổi ý  ";

    @BeforeEach
    void data() {
        saved.put(11L, withId(SalesOrder.create(2, new SalesOrder.Customer(null, "Anh Nam", null, null),
                PriceType.RETAIL, BigDecimal.ZERO.setScale(2), null, 5, NOW.minusHours(1)), 11));
        with(this, "nextPayment", 701L);
    }

    private static CancelSalesOrderRequest refund(long amount, PaymentMethod method, String reference) {
        return new CancelSalesOrderRequest(REASON, new CancelSalesOrderRequest.Refund(amount, method, reference,
                true));
    }

    private static final CancelSalesOrderRequest NO_REFUND = new CancelSalesOrderRequest(REASON, null);

    /** User {@code picker} holds the open picking task of order 10. */
    private void picking(long picker) {
        openPickings.add(withId(WarehouseTask.assign(TaskType.PICKING, 10, 2, picker, 5, NOW.minusHours(1)), 60));
    }

    private SalesOrder cancel(AuthenticatedUser seller, long orderId, CancelSalesOrderRequest request) {
        service.cancel(seller, orderId, request);
        verify(salesOrders).get(seller, orderId);
        SalesOrder found = saved.get(orderId);
        assertThat(found.getStatus()).isEqualTo(SalesOrderStatus.CANCELLED);
        return found;
    }

    private void assertRefused(AuthenticatedUser seller, long orderId, CancelSalesOrderRequest request,
                               HttpStatus status, String code) {
        assertBusiness(() -> service.cancel(seller, orderId, request), status, code);
        verify(warehouseTasks, never()).cancelForDocument(any(), anyLong());
        verify(auditLog, never()).save(any());
        if (saved.containsKey(orderId) && !saved.get(orderId).isDraft()) {
            assertThat(saved.get(orderId).getStatus()).isNotEqualTo(SalesOrderStatus.CANCELLED);
        }
    }

    private void assertRefused(CancelSalesOrderRequest request, HttpStatus status, String code) {
        assertRefused(manager, 10, request, status, code);
    }

    @Test
    @DisplayName("UTCID01 N: Staff 8 holds the picking -> CANCELLED with the reason stripped, Staff 8 told")
    void utc01() {
        picking(8);

        SalesOrder found = cancel(manager, 10, NO_REFUND);

        assertThat(found.getCancellationReason()).isEqualTo("Khách đổi ý");
        verify(warehouseTasks).cancelForDocument(TaskType.PICKING, 10);
        verify(notifications).notify(eq(8L), eq("SALES_ORDER_CANCELLED"), anyString(), anyString(), eq(10L));
    }

    @Test
    @DisplayName("UTCID02 N: 100,000 held, refunded in cash -> CANCELLED, 100,000 refunded")
    void utc02() {
        collected(55, 100_000, true);

        cancel(manager, 10, refund(100_000, PaymentMethod.CASH, null));

        assertThat(collections.of(10).verifiedRefunded()).isEqualTo(100_000);
        assertThat(paid.getLast().getId()).isEqualTo(701);
        assertThat(paid.getLast().getPaymentType()).isEqualTo(PaymentType.REFUND);
    }

    @Test
    @DisplayName("UTCID03 N: Owner cancels order 11, AWAITING_PICKING with nothing collected")
    void utc03() {
        SalesOrder found = cancel(owner, 11, NO_REFUND);

        assertThat(found.getId()).isEqualTo(11);
    }

    @Test
    @DisplayName("UTCID04 B: order 10 already CANCELLED -> shown again, nothing repeated")
    void utc04() {
        order.cancel(5, "Khách đổi ý", NOW.minusMinutes(5));

        cancel(manager, 10, NO_REFUND);

        verify(warehouseTasks, never()).cancelForDocument(any(), anyLong());
        verify(auditLog, never()).save(any());
    }

    @Test
    @DisplayName("UTCID05 B: the Branch Manager holds the picking themself -> CANCELLED, nobody told")
    void utc05() {
        picking(5);

        cancel(manager, 10, NO_REFUND);

        verify(notifications, never()).notify(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID06 A: order 404 does not exist -> MSG156")
    void utc06() {
        assertRefused(manager, 404, NO_REFUND, HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID07 A: Branch Manager 6 of store 9 -> MSG05")
    void utc07() {
        assertRefused(auth(6, Role.BRANCH_MANAGER, 9L), 10, NO_REFUND, HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID08 A: order 10 is COMPLETED -> MSG164")
    void utc08() {
        status(SalesOrderStatus.COMPLETED);

        assertRefused(NO_REFUND, HttpStatus.CONFLICT, "MSG164");
    }

    @Test
    @DisplayName("UTCID09 A: 50,000 waiting for verification -> MSG184")
    void utc09() {
        collected(55, 50_000, false);

        assertRefused(NO_REFUND, HttpStatus.CONFLICT, "MSG184");
    }

    @Test
    @DisplayName("UTCID10 A: 100,000 held, no refund -> MSG185")
    void utc10() {
        collected(55, 100_000, true);

        assertRefused(NO_REFUND, HttpStatus.CONFLICT, "MSG185");
    }

    @Test
    @DisplayName("UTCID11 A: refund 150,000, above the 100,000 held -> MSG183")
    void utc11() {
        collected(55, 100_000, true);

        assertRefused(refund(150_000, PaymentMethod.CASH, null), HttpStatus.BAD_REQUEST, "MSG183");
        assertThat(paid).hasSize(1);
    }

    @Test
    @DisplayName("UTCID12 A: refund 60,000 of 100,000, 40,000 would stay held -> MSG185")
    void utc12() {
        collected(55, 100_000, true);

        assertRefused(refund(60_000, PaymentMethod.CASH, null), HttpStatus.CONFLICT, "MSG185");
    }

    @Test
    @DisplayName("UTCID13 A: refund by bank transfer without a reference -> MSG154")
    void utc13() {
        collected(55, 100_000, true);

        assertRefused(refund(100_000, PaymentMethod.BANK_TRANSFER, null), HttpStatus.BAD_REQUEST, "MSG154");
        assertThat(paid).hasSize(1);
    }

    @Test
    @DisplayName("UTCID14 A: order 10 is still a draft -> MSG156")
    void utc14() {
        draft();

        assertRefused(NO_REFUND, HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID15 A: Staff 7 of store 2 is not a seller -> MSG05")
    void utc15() {
        assertRefused(auth(7, Role.STAFF, 2L), 10, NO_REFUND, HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID16 N: 100,000 refunded by bank transfer \" FT2611180009 \" -> CANCELLED")
    void utc16() {
        collected(55, 100_000, true);

        cancel(manager, 10, refund(100_000, PaymentMethod.BANK_TRANSFER, " FT2611180009 "));

        assertThat(collections.of(10).verifiedRefunded()).isEqualTo(100_000);
        Payment row = paid.getLast();
        assertThat(row.getId()).isEqualTo(701);
        assertThat(row.getTransactionReference()).isEqualTo("FT2611180009");
    }
}
