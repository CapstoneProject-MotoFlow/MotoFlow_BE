package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.sales.dto.ConfirmSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.helper.SalesOrderHistory;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Report 5.1, sheet SalesOrder.confirm: SalesOrderServiceImpl.confirm(seller, id, request), UTCID01-28. Sales order
 * ID = 10 of store 2: PICKED, retail, no discount, version 1; oil requested 2 picked 2 at 100,000, filter requested 1
 * picked 1 at 50,000. Average cost oil 60,000, filter 30,000; sellable oil 5, filter 5; retail price today oil
 * 110,000, filter 50,000; minimum margin 10%; discount threshold 10%.
 */
class SalesOrderConfirmUnitTest extends SalesOrderUnitTestBase {

    private SalesOrder order;
    private SalesOrderLine oil, filter;

    @BeforeEach
    void data() {
        retailPrices.put(FILTER, 50_000L);
        with(parts.get(OIL), "movingAvgCost", new BigDecimal("60000"));
        with(parts.get(FILTER), "movingAvgCost", new BigDecimal("30000"));
        order = picked(PriceType.RETAIL, BigDecimal.ZERO.setScale(2));
    }

    /** Order 10 as picked, with its version 1 in the history; a new order is saved as ID = 11. */
    private SalesOrder picked(PriceType type, BigDecimal rate) {
        saved.clear();
        lines.clear();
        history.clear();
        SalesOrder found = withId(SalesOrder.create(2, new SalesOrder.Customer(null, "Anh Nam", null, null), type,
                rate, null, 5, NOW.minusHours(2)), 10);
        found.picked(NOW.minusHours(1));
        saved.put(10L, found);
        long price = rate.signum() == 0 ? 100_000 : 95_000;
        oil = withId(SalesOrderLine.create(10, 1, OIL, 2, 100_000, price), 1);
        oil.recordPicked(2);
        filter = withId(SalesOrderLine.create(10, 2, FILTER, 1, 50_000, 50_000), 2);
        filter.recordPicked(1);
        lines.addAll(List.of(oil, filter));
        history.add(withId(AuditLog.record(SalesOrderHistory.ENTITY, 10L, SalesOrderHistory.CREATED, 5L, null, null,
                SalesOrderHistory.snapshot(1, null, found, lines), NOW.minusHours(2)), 900));
        with(this, "nextOrder", 11L);
        return found;
    }

    /** Order 10 confirmed before with oil 2 and filter 1. */
    private void confirmedBefore() {
        oil.deliver(2);
        filter.deliver(1);
        order.confirm(5, NOW.minusMinutes(30));
    }

    private static ConfirmSalesOrderRequest.Line line(long productId, int delivered) {
        return new ConfirmSalesOrderRequest.Line(productId, delivered);
    }

    private static ConfirmSalesOrderRequest request(ConfirmSalesOrderRequest.Line... lines) {
        return new ConfirmSalesOrderRequest(1, true, List.of(lines));
    }

    private static ConfirmSalesOrderRequest asPicked() {
        return request(line(OIL, 2), line(FILTER, 1));
    }

    private static SalesOrderDetail.Line row(SalesOrderDetail detail, long productId) {
        return detail.lines().stream().filter(l -> l.productId() == productId).findFirst().orElseThrow();
    }

    private SalesOrderDetail confirm(AuthenticatedUser seller, ConfirmSalesOrderRequest request) {
        SalesOrderDetail detail = service.confirm(seller, 10, request);
        assertThat(detail.id()).isEqualTo(10);
        assertThat(detail.status()).isEqualTo(SalesOrderStatus.CONFIRMED);
        return detail;
    }

    private void assertRefused(AuthenticatedUser seller, ConfirmSalesOrderRequest request, HttpStatus status,
                               String code) {
        SalesOrderStatus before = saved.containsKey(10L) ? saved.get(10L).getStatus() : null;
        assertBusiness(() -> service.confirm(seller, 10, request), status, code);
        verify(warehouseTasks, never()).confirmResult(any(), any(), anyLong());
        verify(auditLog, never()).save(any());
        if (before != null) {
            assertThat(saved.get(10L).getStatus()).isEqualTo(before);
        }
    }

    private void assertRefused(ConfirmSalesOrderRequest request, HttpStatus status, String code) {
        assertRefused(manager, request, status, code);
    }

    @Test
    @DisplayName("UTCID01 N: delivered as picked -> CONFIRMED, version 1, oil still at 100,000")
    void utc01() {
        SalesOrderDetail detail = confirm(manager, asPicked());

        assertThat(detail.version()).isEqualTo(1);
        assertThat(detail.confirmedById()).isEqualTo(5L);
        assertThat(row(detail, OIL).deliveredQty()).isEqualTo(2);
        assertThat(row(detail, OIL).actualUnitPrice()).isEqualTo(100_000);
        verify(warehouseTasks).confirmResult(manager, TaskType.PICKING, 10);
    }

    @Test
    @DisplayName("UTCID02 N: oil delivered 1 -> version 2 at today's price 110,000")
    void utc02() {
        SalesOrderDetail detail = confirm(manager, request(line(OIL, 1), line(FILTER, 1)));

        assertThat(detail.version()).isEqualTo(2);
        assertThat(row(detail, OIL).deliveredQty()).isEqualTo(1);
        assertThat(row(detail, OIL).actualUnitPrice()).isEqualTo(110_000);
    }

    @Test
    @DisplayName("UTCID03 N: Owner confirms")
    void utc03() {
        SalesOrderDetail detail = confirm(owner, asPicked());

        assertThat(detail.confirmedById()).isEqualTo(1L);
    }

    @Test
    @DisplayName("UTCID04 N: already CONFIRMED with the same quantities -> unchanged, version 1")
    void utc04() {
        confirmedBefore();

        SalesOrderDetail detail = confirm(manager, asPicked());

        assertThat(detail.version()).isEqualTo(1);
        verify(warehouseTasks, never()).confirmResult(any(), any(), anyLong());
        verify(auditLog, never()).save(any());
    }

    @Test
    @DisplayName("UTCID05 A: order 10 does not exist -> MSG156")
    void utc05() {
        saved.clear();

        assertRefused(asPicked(), HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID06 A: Branch Manager 7 of store 3 -> MSG05")
    void utc06() {
        assertRefused(auth(7, Role.BRANCH_MANAGER, 3L), asPicked(), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID07 A: order 10 is AWAITING_PICKING -> MSG174")
    void utc07() {
        with(order, "status", SalesOrderStatus.AWAITING_PICKING);

        assertRefused(asPicked(), HttpStatus.CONFLICT, "MSG174");
    }

    @Test
    @DisplayName("UTCID08 A: order 10 is COMPLETED -> MSG164")
    void utc08() {
        with(order, "status", SalesOrderStatus.COMPLETED);

        assertRefused(asPicked(), HttpStatus.CONFLICT, "MSG164");
    }

    @Test
    @DisplayName("UTCID09 A: version 2 was saved since -> MSG165")
    void utc09() {
        history.add(withId(AuditLog.record(SalesOrderHistory.ENTITY, 10L, SalesOrderHistory.VERSIONED, 5L, null,
                null, SalesOrderHistory.snapshot(2, null, order, lines), NOW.minusMinutes(10)), 901));

        assertRefused(asPicked(), HttpStatus.CONFLICT, "MSG165");
    }

    @Test
    @DisplayName("UTCID10 A: part 999 is not on the order -> MSG166 for row 2")
    void utc10() {
        assertRefused(request(line(OIL, 2), line(999, 1)), HttpStatus.BAD_REQUEST, "MSG166");
    }

    @Test
    @DisplayName("UTCID11 A: the filter line is left out -> MSG167")
    void utc11() {
        assertRefused(request(line(OIL, 2)), HttpStatus.BAD_REQUEST, "MSG167");
    }

    @Test
    @DisplayName("UTCID12 A: only 1 oil sellable -> MSG171")
    void utc12() {
        sellable.put(OIL, 1);

        assertRefused(asPicked(), HttpStatus.CONFLICT, "MSG171");
    }

    @Test
    @DisplayName("UTCID13 A: minimum margin 100%, minimum 300,000 > total 250,000 -> MSG172")
    void utc13() {
        margin = new BigDecimal("100");

        assertRefused(asPicked(), HttpStatus.BAD_REQUEST, "MSG172");
    }

    @Test
    @DisplayName("UTCID14 A: the same for Owner -> MSG173 with the amount")
    void utc14() {
        margin = new BigDecimal("100");

        assertRefused(owner, asPicked(), HttpStatus.BAD_REQUEST, "MSG173");
    }

    @Test
    @DisplayName("UTCID15 B: oil delivered 3, above the 2 picked -> MSG169")
    void utc15() {
        assertRefused(request(line(OIL, 3), line(FILTER, 1)), HttpStatus.BAD_REQUEST, "MSG169");
    }

    @Test
    @DisplayName("UTCID16 B: nothing delivered -> MSG170")
    void utc16() {
        assertRefused(request(line(OIL, 0), line(FILTER, 0)), HttpStatus.BAD_REQUEST, "MSG170");
    }

    @Test
    @DisplayName("UTCID17 B: oil delivered 0 -> CONFIRMED")
    void utc17() {
        SalesOrderDetail detail = confirm(manager, request(line(OIL, 0), line(FILTER, 1)));

        assertThat(row(detail, OIL).deliveredQty()).isZero();
    }

    @Test
    @DisplayName("UTCID18 B: sellable oil 2 and filter 1, exactly what is delivered -> CONFIRMED")
    void utc18() {
        sellable.put(OIL, 2);
        sellable.put(FILTER, 1);

        SalesOrderDetail detail = confirm(manager, asPicked());

        assertThat(row(detail, OIL).deliveredQty()).isEqualTo(2);
        assertThat(row(detail, FILTER).deliveredQty()).isEqualTo(1);
    }

    @Test
    @DisplayName("UTCID19 N: confirmed again with oil 1 instead of 2 -> version 2 at today's price")
    void utc19() {
        confirmedBefore();

        SalesOrderDetail detail = confirm(manager, request(line(OIL, 1), line(FILTER, 1)));

        assertThat(detail.version()).isEqualTo(2);
        assertThat(row(detail, OIL).deliveredQty()).isEqualTo(1);
        assertThat(row(detail, OIL).actualUnitPrice()).isEqualTo(110_000);
    }

    @Test
    @DisplayName("UTCID20 A: Staff 7 of store 2 is not a seller -> MSG05")
    void utc20() {
        assertRefused(auth(7, Role.STAFF, 2L), asPicked(), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID21 A: order 10 is still a draft -> MSG156")
    void utc21() {
        saved.put(10L, withId(SalesOrder.createDraft(2, new SalesOrder.Customer(null, null, null, null),
                PriceType.RETAIL, BigDecimal.ZERO.setScale(2), null, 5, NOW), 10));

        assertRefused(asPicked(), HttpStatus.NOT_FOUND, "MSG156");
    }

    @Test
    @DisplayName("UTCID22 A: oil listed twice -> MSG166 for row 2")
    void utc22() {
        assertRefused(request(line(OIL, 2), line(OIL, 1)), HttpStatus.BAD_REQUEST, "MSG166");
    }

    @Test
    @DisplayName("UTCID23 B: the filter has no moving average cost yet -> counts 0, CONFIRMED")
    void utc23() {
        with(parts.get(FILTER), "movingAvgCost", null);

        SalesOrderDetail detail = confirm(manager, asPicked());

        assertThat(row(detail, FILTER).deliveredQty()).isEqualTo(1);
    }

    @Test
    @DisplayName("UTCID24 A: wholesale 5% with only oil 2 at 95,000, threshold lowered to 4% -> MSG162")
    void utc24() {
        order = picked(PriceType.WHOLESALE, new BigDecimal("5.00"));
        lines.remove(filter);
        threshold = new BigDecimal("4");

        assertRefused(request(line(OIL, 2)), HttpStatus.BAD_REQUEST, "MSG162");
    }

    @Test
    @DisplayName("UTCID25 A: oil delivered 1 (new version), the filter has no retail price now -> MSG160")
    void utc25() {
        retailPrices.remove(FILTER);

        assertRefused(request(line(OIL, 1), line(FILTER, 1)), HttpStatus.BAD_REQUEST, "MSG160");
    }

    @Test
    @DisplayName("UTCID26 A: order 10 is a waiting order (AWAITING_STOCK) -> MSG308")
    void utc26() {
        with(order, "status", SalesOrderStatus.AWAITING_STOCK);

        assertRefused(asPicked(), HttpStatus.CONFLICT, "MSG308");
    }

    @Test
    @DisplayName("UTCID27 N: oil delivered 1, the customer waits for the other -> waiting order 11 with oil 1")
    void utc27() {
        SalesOrderDetail detail = confirm(manager, new ConfirmSalesOrderRequest(1, true, List.of(
                new ConfirmSalesOrderRequest.Line(OIL, 1, true), line(FILTER, 1))));

        assertThat(detail.version()).isEqualTo(2);
        assertThat(row(detail, OIL).requestedQty()).isEqualTo(1);
        assertThat(row(detail, OIL).deliveredQty()).isEqualTo(1);
        assertThat(detail.split()).isEqualTo(new SalesOrderDetail.Split(10L, 11L, null, List.of()));
        assertThat(saved.get(11L).getStatus()).isEqualTo(SalesOrderStatus.AWAITING_STOCK);
        assertThat(linesOf(11)).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(OIL);
            assertThat(l.getRequestedQty()).isEqualTo(1);
            assertThat(l.getActualUnitPrice()).isEqualTo(110_000);
        });
    }

    @Test
    @DisplayName("UTCID28 B: filter delivered 0, the customer waits for it -> the line moves to waiting order 11")
    void utc28() {
        SalesOrderDetail detail = confirm(manager, new ConfirmSalesOrderRequest(1, true, List.of(line(OIL, 2),
                new ConfirmSalesOrderRequest.Line(FILTER, 0, true))));

        assertThat(detail.lines()).singleElement().satisfies(l -> assertThat(l.productId()).isEqualTo(OIL));
        assertThat(detail.split()).isEqualTo(new SalesOrderDetail.Split(10L, 11L, null, List.of()));
        assertThat(linesOf(11)).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(FILTER);
            assertThat(l.getRequestedQty()).isEqualTo(1);
        });
    }
}
