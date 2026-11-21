package fu.hl.g64.motorflow.receiving.unit;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderLineRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderRequest;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet PurchaseOrder.create: PurchaseOrderServiceImpl.create(owner, request), UTCID01-15. */
class PurchaseOrderCreateUnitTest extends PurchaseOrderUnitTestBase {

    private static final String NOTE = "  Giao buổi sáng ";

    private static PurchaseOrderRequest request(LocalDate arrival, String note, Long replenishmentId,
                                                PurchaseOrderLineRequest... lines) {
        return new PurchaseOrderRequest(3L, 2L, arrival, note, List.of(lines), replenishmentId, false);
    }

    private static PurchaseOrderRequest oneLine(LocalDate arrival) {
        return request(arrival, NOTE, null, line(PAD, 1, 1));
    }

    private void assertPlaced(PurchaseOrderDetail result) {
        assertThat(result.id()).isEqualTo(30);
        assertThat(result.status()).isEqualTo(PurchaseOrderStatus.AWAITING_DELIVERY);
    }

    private void assertRefused(PurchaseOrderRequest request, HttpStatus status, String code) {
        assertBusiness(() -> service.create(owner, request), status, code);
        verify(orders, never()).save(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    @DisplayName("UTCID01 N: two lines, expected arrival after today, note stripped")
    void utc01() {
        PurchaseOrderDetail result = service.create(owner, request(LocalDate.of(2026, 11, 21), NOTE, null,
                line(PAD, 10, 60000), line(OIL, 5, 70000)));

        assertPlaced(result);
        assertThat(result.supplierId()).isEqualTo(3);
        assertThat(result.storeId()).isEqualTo(2);
        assertThat(result.expectedArrival()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(result.note()).isEqualTo("Giao buổi sáng");
        assertThat(result.lines()).extracting(PurchaseOrderDetail.Line::productId,
                PurchaseOrderDetail.Line::orderedQty, PurchaseOrderDetail.Line::purchasePrice)
                .containsExactly(tuple(PAD, 10, 60000L), tuple(OIL, 5, 70000L));
        verify(auditLog).save(any(AuditLog.class));
        verify(notifications).notifyStoreManagers(eq(2L), eq("PURCHASE_ORDER_CREATED"), anyString(), anyString(),
                eq(30L));
    }

    @Test
    @DisplayName("UTCID02 N: PO for replenishment request 8 asks it to prepare part 10 x 10")
    void utc02() {
        PurchaseOrderDetail result = service.create(owner, request(null, NOTE, 8L, line(PAD, 10, 60000)));

        assertPlaced(result);
        assertThat(result.replenishmentRequestId()).isEqualTo(8L);
        assertThat(result.lines()).extracting(PurchaseOrderDetail.Line::orderedQty).containsExactly(10);
        verify(replenishments).prepareForPurchase(owner, 8L, 2L, Map.of(PAD, 10), false);
    }

    @Test
    @DisplayName("UTCID03 B: expected arrival today (lowest valid date)")
    void utc03() {
        PurchaseOrderDetail result = service.create(owner, oneLine(LocalDate.of(2026, 11, 18)));

        assertPlaced(result);
        assertThat(result.expectedArrival()).isEqualTo(LocalDate.of(2026, 11, 18));
    }

    @Test
    @DisplayName("UTCID04 B: expected arrival yesterday -> MSG248")
    void utc04() {
        assertRefused(oneLine(LocalDate.of(2026, 11, 17)), HttpStatus.BAD_REQUEST, "MSG248");
    }

    @Test
    @DisplayName("UTCID05 B: quantity 0 on line 2 -> MSG20")
    void utc05() {
        assertRefused(request(null, NOTE, null, line(PAD, 1, 1), line(OIL, 0, 70000)), HttpStatus.BAD_REQUEST,
                "MSG20");
    }

    @Test
    @DisplayName("UTCID06 B: purchase price 0 -> MSG20")
    void utc06() {
        assertRefused(request(null, NOTE, null, line(PAD, 1, 0)), HttpStatus.BAD_REQUEST, "MSG20");
    }

    @Test
    @DisplayName("UTCID07 A: the same part on two lines -> MSG20")
    void utc07() {
        assertRefused(request(null, NOTE, null, line(PAD, 1, 1), line(PAD, 2, 1)), HttpStatus.BAD_REQUEST, "MSG20");
    }

    @Test
    @DisplayName("UTCID08 A: supplier 3 does not exist -> MSG73")
    void utc08() {
        when(suppliers.findById(3L)).thenReturn(Optional.empty());

        assertRefused(oneLine(null), HttpStatus.BAD_REQUEST, "MSG73");
    }

    @Test
    @DisplayName("UTCID09 A: supplier 3 INACTIVE -> MSG129")
    void utc09() {
        with(supplier, "status", SupplierStatus.INACTIVE);

        assertRefused(oneLine(null), HttpStatus.BAD_REQUEST, "MSG129");
    }

    @Test
    @DisplayName("UTCID10 A: warehouse 2 INACTIVE -> MSG46")
    void utc10() {
        with(warehouse, "status", StoreStatus.INACTIVE);

        assertRefused(oneLine(null), HttpStatus.BAD_REQUEST, "MSG46");
    }

    @Test
    @DisplayName("UTCID11 A: part 99 does not exist -> MSG57")
    void utc11() {
        assertRefused(request(null, NOTE, null, line(UNKNOWN, 1, 1)), HttpStatus.BAD_REQUEST, "MSG57");
    }

    @Test
    @DisplayName("UTCID12 A: part 12 DISCONTINUED -> MSG21")
    void utc12() {
        assertRefused(request(null, NOTE, null, line(OLD, 1, 1)), HttpStatus.BAD_REQUEST, "MSG21");
    }

    @Test
    @DisplayName("UTCID13 B: blank note is saved as null")
    void utc13() {
        PurchaseOrderDetail result = service.create(owner, request(LocalDate.of(2026, 11, 21), "   ", null,
                line(PAD, 10, 60000)));

        assertPlaced(result);
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("UTCID14 B: expected arrival not given stays null")
    void utc14() {
        PurchaseOrderDetail result = service.create(owner, oneLine(null));

        assertPlaced(result);
        assertThat(result.expectedArrival()).isNull();
        verifyNoInteractions(replenishments);
    }

    @Test
    @DisplayName("UTCID15 A: part 14 is the dented-goods variant of part 10 -> MSG316")
    void utc15() {
        Product variant = withId(Product.damagedVariantOf(parts.get(PAD), 1L, NOW), 14);
        parts.put(14L, with(variant, "status", ProductStatus.ACTIVE));

        assertRefused(request(null, NOTE, null, line(14, 1, 1)), HttpStatus.BAD_REQUEST, "MSG316");
    }
}
