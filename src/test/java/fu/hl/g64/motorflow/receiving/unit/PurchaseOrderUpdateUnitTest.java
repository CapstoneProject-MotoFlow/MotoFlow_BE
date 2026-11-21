package fu.hl.g64.motorflow.receiving.unit;

import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderLineRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderUpdateRequest;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrderLine;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet PurchaseOrder.update: PurchaseOrderServiceImpl.update(owner, id, request), UTCID01-22. */
class PurchaseOrderUpdateUnitTest extends PurchaseOrderUnitTestBase {

    private static final LocalDate EXPECTED = LocalDate.of(2026, 11, 20);
    private static final String REASON = " Nhà cung cấp đổi giá ";

    private PurchaseOrder order;

    /** PO 30: placed, AWAITING_DELIVERY, expected 20/11, note "Giao sáng", part 10 x 10 at 60,000, part 11 x 5 at 70,000. */
    @BeforeEach
    void placedOrder() {
        order = withId(PurchaseOrder.create(3, 2, EXPECTED, "Giao sáng", 1, NOW.minusDays(2)), 30);
        savedLines.add(withId(PurchaseOrderLine.create(30, PAD, 10, 60000), 1));
        savedLines.add(withId(PurchaseOrderLine.create(30, OIL, 5, 70000), 2));
        when(orders.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(30L)).thenAnswer(inv -> Optional.of(order));
        when(receipts.existsByPurchaseOrderId(30L)).thenReturn(false);
        org.mockito.Mockito.doAnswer(inv -> {
            savedLines.removeAll((List<PurchaseOrderLine>) inv.getArgument(0));
            return null;
        }).when(orderLines).deleteAll(any());
    }

    private static PurchaseOrderUpdateRequest request(LocalDate arrival, String note, PurchaseOrderLineRequest... lines) {
        return new PurchaseOrderUpdateRequest(arrival, note, List.of(lines), REASON);
    }

    private static PurchaseOrderUpdateRequest same(PurchaseOrderLineRequest... lines) {
        return request(EXPECTED, "Giao sáng", lines);
    }

    private PurchaseOrderDetail update(PurchaseOrderUpdateRequest request) {
        PurchaseOrderDetail result = service.update(owner, 30, request);
        assertThat(result.id()).isEqualTo(30);
        assertThat(result.status()).isEqualTo(PurchaseOrderStatus.AWAITING_DELIVERY);
        return result;
    }

    private static List<org.assertj.core.groups.Tuple> lines(PurchaseOrderDetail result) {
        return result.lines().stream().map(l -> tuple(l.productId(), l.orderedQty(), l.purchasePrice())).toList();
    }

    private void assertLogged() {
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLog).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("Nhà cung cấp đổi giá");
        verify(notifications).notifyStoreManagers(eq(2L), eq("PURCHASE_ORDER_UPDATED"), anyString(), anyString(),
                eq(30L));
    }

    private void assertRefused(PurchaseOrderUpdateRequest request, HttpStatus status, String code) {
        assertBusiness(() -> service.update(owner, 30, request), status, code);
        verify(auditLog, never()).save(any());
        verify(orderLines, never()).saveAll(any());
    }

    @Test
    @DisplayName("UTCID01 N: part 10 becomes 12 x 58,000; logged with the reason")
    void utc01() {
        PurchaseOrderDetail result = update(same(line(PAD, 12, 58000), line(OIL, 5, 70000)));

        assertThat(lines(result)).containsExactly(tuple(PAD, 12, 58000L), tuple(OIL, 5, 70000L));
        assertLogged();
    }

    @Test
    @DisplayName("UTCID02 N: part 11 removed, part 13 x 4 added")
    void utc02() {
        PurchaseOrderDetail result = update(same(line(PAD, 10, 60000), line(LAMP, 4, 25000)));

        assertThat(lines(result)).containsExactly(tuple(PAD, 10, 60000L), tuple(LAMP, 4, 25000L));
        assertLogged();
    }

    @Test
    @DisplayName("UTCID03 N: new expected arrival 23/11 and note")
    void utc03() {
        PurchaseOrderDetail result = update(request(LocalDate.of(2026, 11, 23), "Giao chiều", line(PAD, 10, 60000),
                line(OIL, 5, 70000)));

        assertThat(result.expectedArrival()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(result.note()).isEqualTo("Giao chiều");
        assertLogged();
    }

    @Test
    @DisplayName("UTCID04 B: nothing changed -> returned as it is, no log")
    void utc04() {
        PurchaseOrderDetail result = update(same(line(PAD, 10, 60000), line(OIL, 5, 70000)));

        assertThat(result.expectedArrival()).isEqualTo(EXPECTED);
        assertThat(result.note()).isEqualTo("Giao sáng");
        assertThat(lines(result)).containsExactly(tuple(PAD, 10, 60000L), tuple(OIL, 5, 70000L));
        verify(auditLog, never()).save(any());
        verify(notifications, never()).notifyStoreManagers(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID05 B: the PO already expects 17/11 (past) and keeps it while part 10 becomes 11")
    void utc05() {
        with(order, "expectedArrival", LocalDate.of(2026, 11, 17));

        PurchaseOrderDetail result = update(request(LocalDate.of(2026, 11, 17), "Giao sáng", line(PAD, 11, 60000),
                line(OIL, 5, 70000)));

        assertThat(result.expectedArrival()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(lines(result)).first().isEqualTo(tuple(PAD, 11, 60000L));
    }

    @Test
    @DisplayName("UTCID06 B: a new expected arrival yesterday -> MSG248")
    void utc06() {
        assertRefused(request(LocalDate.of(2026, 11, 17), "Giao sáng", line(PAD, 10, 60000), line(OIL, 5, 70000)),
                HttpStatus.BAD_REQUEST, "MSG248");
    }

    @Test
    @DisplayName("UTCID07 A: PO 30 does not exist -> MSG131")
    void utc07() {
        when(orders.findByIdForUpdate(30L)).thenReturn(Optional.empty());

        assertRefused(request(null, null, line(PAD, 1, 1)), HttpStatus.NOT_FOUND, "MSG131");
    }

    @Test
    @DisplayName("UTCID08 A: PO 30 cancelled -> MSG130")
    void utc08() {
        with(order, "status", PurchaseOrderStatus.CANCELLED);

        assertRefused(request(null, null, line(PAD, 1, 1)), HttpStatus.CONFLICT, "MSG130");
    }

    @Test
    @DisplayName("UTCID09 A: a delivery was already recorded -> MSG25")
    void utc09() {
        when(receipts.existsByPurchaseOrderId(30L)).thenReturn(true);

        assertRefused(request(null, null, line(PAD, 1, 1)), HttpStatus.CONFLICT, "MSG25");
    }

    @Test
    @DisplayName("UTCID10 A: adding discontinued part 12 -> MSG21")
    void utc10() {
        assertRefused(same(line(PAD, 10, 60000), line(OLD, 1, 1)), HttpStatus.BAD_REQUEST, "MSG21");
    }

    @Test
    @DisplayName("UTCID11 N: part 12 discontinued since but already on the PO may change to 3")
    void utc11() {
        savedLines.remove(1);
        savedLines.add(withId(PurchaseOrderLine.create(30, OLD, 2, 10000), 3));

        PurchaseOrderDetail result = update(same(line(PAD, 10, 60000), line(OLD, 3, 10000)));

        assertThat(lines(result)).containsExactly(tuple(PAD, 10, 60000L), tuple(OLD, 3, 10000L));
    }

    @Test
    @DisplayName("UTCID12 B: quantity 0 -> MSG20")
    void utc12() {
        assertRefused(same(line(PAD, 0, 60000)), HttpStatus.BAD_REQUEST, "MSG20");
    }

    @Test
    @DisplayName("UTCID13 N: only the price of part 10 changes")
    void utc13() {
        PurchaseOrderDetail result = update(same(line(PAD, 10, 55000), line(OIL, 5, 70000)));

        assertThat(lines(result)).first().isEqualTo(tuple(PAD, 10, 55000L));
        assertLogged();
    }

    @Test
    @DisplayName("UTCID14 B: blank note clears the note")
    void utc14() {
        PurchaseOrderDetail result = update(request(EXPECTED, "   ", line(PAD, 10, 60000), line(OIL, 5, 70000)));

        assertThat(result.note()).isNull();
        assertThat(result.expectedArrival()).isEqualTo(EXPECTED);
        assertLogged();
    }

    @Test
    @DisplayName("UTCID15 B: expected arrival cleared")
    void utc15() {
        PurchaseOrderDetail result = update(request(null, "Giao sáng", line(PAD, 10, 60000), line(OIL, 5, 70000)));

        assertThat(result.expectedArrival()).isNull();
        assertLogged();
    }

    @Test
    @DisplayName("UTCID16 N: part 13 x 4 added, the other lines unchanged")
    void utc16() {
        PurchaseOrderDetail result = update(same(line(PAD, 10, 60000), line(OIL, 5, 70000), line(LAMP, 4, 25000)));

        assertThat(lines(result)).containsExactly(tuple(PAD, 10, 60000L), tuple(OIL, 5, 70000L),
                tuple(LAMP, 4, 25000L));
        assertLogged();
    }

    @Test
    @DisplayName("UTCID17 A: PO 30 is still a draft -> MSG131")
    void utc17() {
        with(order, "status", PurchaseOrderStatus.DRAFT);

        assertRefused(same(line(PAD, 12, 58000)), HttpStatus.NOT_FOUND, "MSG131");
    }

    @Test
    @DisplayName("UTCID18 A: PO 30 is RECEIVING -> MSG25")
    void utc18() {
        with(order, "status", PurchaseOrderStatus.RECEIVING);

        assertRefused(same(line(PAD, 12, 58000)), HttpStatus.CONFLICT, "MSG25");
    }

    @Test
    @DisplayName("UTCID19 B: price 0 on line 2 -> MSG20")
    void utc19() {
        assertRefused(same(line(PAD, 10, 60000), line(OIL, 5, 0)), HttpStatus.BAD_REQUEST, "MSG20");
    }

    @Test
    @DisplayName("UTCID20 A: part 10 on two lines -> MSG20")
    void utc20() {
        assertRefused(same(line(PAD, 10, 60000), line(PAD, 2, 60000)), HttpStatus.BAD_REQUEST, "MSG20");
    }

    @Test
    @DisplayName("UTCID21 A: part 99 does not exist -> MSG57")
    void utc21() {
        assertRefused(same(line(PAD, 10, 60000), line(UNKNOWN, 1, 1000)), HttpStatus.BAD_REQUEST, "MSG57");
    }

    @Test
    @DisplayName("UTCID22 A: adding the dented-goods variant (part 14) -> MSG316")
    void utc22() {
        parts.put(14L, withId(fu.hl.g64.motorflow.catalog.entity.Product.damagedVariantOf(parts.get(PAD), 1L, NOW),
                14));

        assertRefused(same(line(PAD, 10, 60000), line(14, 1, 1000)), HttpStatus.BAD_REQUEST, "MSG316");
    }
}
