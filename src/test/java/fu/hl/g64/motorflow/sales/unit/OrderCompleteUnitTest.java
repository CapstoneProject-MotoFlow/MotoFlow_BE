package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.sales.dto.CompleteSalesOrderRequest;
import fu.hl.g64.motorflow.sales.entity.enums.CompletionType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Report 5.1, sheet OrderPayment.complete: SalesOrderSettlementServiceImpl.complete(seller, orderId, request),
 * UTCID01-16. The returned SalesOrderDetail comes from SalesOrderService.get; the values the sheet lists are read
 * from order 10 and its payments as this call left them.
 */
class OrderCompleteUnitTest extends SettlementUnitTestBase {

    private static final CompleteSalesOrderRequest VERSION_1 = new CompleteSalesOrderRequest(1);

    private void complete(AuthenticatedUser seller) {
        service.complete(seller, 10, VERSION_1);
        verify(salesOrders).get(seller, 10);
        assertThat(order.getId()).isEqualTo(10);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.COMPLETED);
        assertThat(order.getCompletionType()).isEqualTo(CompletionType.PAID);
    }

    private void assertRefused(AuthenticatedUser seller, long orderId, HttpStatus status, String code) {
        assertBusiness(() -> service.complete(seller, orderId, VERSION_1), status, code);
        verify(inventory, never()).takeOutAll(anyList());
        verify(auditLog, never()).save(any());
    }

    private void assertRefused(HttpStatus status, String code) {
        assertRefused(manager, 10, status, code);
    }

    @Test
    @DisplayName("UTCID01 N: 250,000 verified -> COMPLETED")
    void utc01() {
        collected(55, 250_000, true);

        complete(manager);

        assertThat(collections.of(10).netCollected()).isEqualTo(250_000);
        verify(inventory).takeOutAll(anyList());
    }

    @Test
    @DisplayName("UTCID02 N: Owner; 300,000 paid and 50,000 refunded, all verified -> COMPLETED")
    void utc02() {
        collected(55, 300_000, true);
        refunded(56, 50_000);

        complete(owner);

        assertThat(collections.of(10).netCollected()).isEqualTo(250_000);
    }

    @Test
    @DisplayName("UTCID03 B: filter delivered 0 (total 200,000), 200,000 verified -> COMPLETED")
    void utc03() {
        filter.deliver(0);
        collected(55, 200_000, true);

        complete(manager);

        assertThat(filter.getDeliveredQty()).isZero();
    }

    @Test
    @DisplayName("UTCID04 B: sellable oil 2 and filter 1, exactly what is delivered -> COMPLETED")
    void utc04() {
        collected(55, 250_000, true);
        sellable.put(OIL, 2);
        sellable.put(FILTER, 1);

        complete(manager);
    }

    @Test
    @DisplayName("UTCID05 A: Branch Manager 6 of store 9 -> MSG05")
    void utc05() {
        collected(55, 250_000, true);

        assertRefused(auth(6, Role.BRANCH_MANAGER, 9L), 10, HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID06 A: order 10 is PICKED, not confirmed -> MSG179")
    void utc06() {
        status(SalesOrderStatus.PICKED);

        assertRefused(HttpStatus.CONFLICT, "MSG179");
    }

    @Test
    @DisplayName("UTCID07 A: version 2 was saved since -> MSG165")
    void utc07() {
        collected(55, 250_000, true);
        versionSavedSince();

        assertRefused(HttpStatus.CONFLICT, "MSG165");
    }

    @Test
    @DisplayName("UTCID08 A: 50,000 still waiting for verification -> MSG184")
    void utc08() {
        collected(55, 250_000, true);
        collected(56, 50_000, false);

        assertRefused(HttpStatus.CONFLICT, "MSG184");
    }

    @Test
    @DisplayName("UTCID09 A: 200,000 verified, 50,000 unpaid -> MSG186")
    void utc09() {
        collected(55, 200_000, true);

        assertRefused(HttpStatus.CONFLICT, "MSG186");
    }

    @Test
    @DisplayName("UTCID10 A: 270,000 verified, 20,000 overpaid -> MSG187")
    void utc10() {
        collected(55, 270_000, true);

        assertRefused(HttpStatus.CONFLICT, "MSG187");
    }

    @Test
    @DisplayName("UTCID11 B: only 1 oil sellable -> MSG171")
    void utc11() {
        collected(55, 250_000, true);
        sellable.put(OIL, 1);

        assertRefused(HttpStatus.CONFLICT, "MSG171");
    }

    @Test
    @DisplayName("UTCID12 B: 5% off, only oil 2 x 95,000 (no filter line), threshold now 5% -> MSG162")
    void utc12() {
        saved.clear();
        lines.clear();
        order = confirmedOrder(10, new BigDecimal("5.00"), 95_000);
        lines.remove(filter);
        collected(55, 190_000, true);
        threshold = new BigDecimal("5");

        assertRefused(HttpStatus.BAD_REQUEST, "MSG162");
    }

    @Test
    @DisplayName("UTCID13 A: order 10 is COMPLETED -> MSG164")
    void utc13() {
        status(SalesOrderStatus.COMPLETED);

        assertRefused(HttpStatus.CONFLICT, "MSG164");
    }

    @Test
    @DisplayName("UTCID14 A: order 404 does not exist -> MSG156")
    void utc14() {
        assertRefused(manager, 404, HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID15 A: order 10 is still a draft -> MSG156")
    void utc15() {
        draft();

        assertRefused(HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID16 A: Staff 7 of store 2 is not a seller -> MSG05")
    void utc16() {
        assertRefused(auth(7, Role.STAFF, 2L), 10, HttpStatus.FORBIDDEN, "MSG05");
    }
}
