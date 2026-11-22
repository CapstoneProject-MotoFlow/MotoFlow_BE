package fu.hl.g64.motorflow.receiving.unit;

import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptRequest;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptRequest.DocumentLine;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet GoodsReceipt.create: GoodsReceiptServiceImpl.create(user, poId, request), UTCID01-21. */
class GoodsReceiptCreateUnitTest extends GoodsReceiptUnitTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private static final List<AttachmentUpload> PHOTO = List.of(new AttachmentUpload("grn/px-001.jpg", "px-001.jpg",
            "image/jpeg", 1000L));
    private static final List<DocumentLine> BOTH = List.of(new DocumentLine(PAD, 10), new DocumentLine(OIL, 5));

    private DiscrepancyCase case77;
    private int pendingOn77;

    /** PO 30 placed, nothing received; case 77 of the earlier GRN 40 of PO 30: part 10 short 3, open. */
    @BeforeEach
    void data() {
        when(orders.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(30L)).thenAnswer(inv -> Optional.of(order));
        case77 = withId(DiscrepancyCase.supplier(40, PAD, 3, 0, 0, 3, NOW.minusDays(1)), 77);
        when(discrepancies.findAllByIdForUpdate(any())).thenAnswer(inv -> List.of(case77));
        when(discrepancies.findAllById(any())).thenAnswer(inv -> List.of(case77));
        GoodsReceiptNote grn40 = withId(GoodsReceiptNote.supplierDelivery(30, "PX-000", TODAY, null, null,
                NOW.minusDays(1)), 40);
        when(receipts.findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(30L)).thenReturn(List.of(grn40));
    }

    @Override
    DiscrepancySettlement.Progress pending(long id) {
        return new DiscrepancySettlement.Progress(0, 0, 0, 0, id == 77 ? pendingOn77 : 0, null, 0, 0, 0, 0, 0);
    }

    private static GoodsReceiptRequest request(String documentNo, LocalDate documentDate, LocalDateTime arrivedAt,
                                               List<Long> caseIds, List<DocumentLine> lines) {
        return new GoodsReceiptRequest(documentNo, documentDate, arrivedAt, "Anh Tài", "29C-12345", caseIds, PHOTO,
                lines);
    }

    private static GoodsReceiptRequest delivery(String documentNo) {
        return request(documentNo, TODAY, null, null, BOTH);
    }

    private static GoodsReceiptRequest replacement(List<DocumentLine> lines) {
        return request("PX-003", TODAY, null, List.of(77L), lines);
    }

    private GoodsReceiptDetail create(GoodsReceiptRequest request) {
        GoodsReceiptDetail result = service.create(staff, 30, request);
        assertThat(result.id()).isEqualTo(50);
        assertThat(result.status()).isEqualTo(GoodsReceiptNoteStatus.AWAITING_INSPECTION);
        assertThat(result.purchaseOrderId()).isEqualTo(30);
        return result;
    }

    private static List<org.assertj.core.groups.Tuple> lines(GoodsReceiptDetail result) {
        return result.lines().stream().map(l -> tuple(l.productId(), l.documentQty(), l.expectedQty())).toList();
    }

    private void assertRefused(GoodsReceiptRequest request, HttpStatus status, String code) {
        assertBusiness(() -> service.create(staff, 30, request), status, code);
        verify(receipts, never()).save(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    @DisplayName("UTCID01 N: delivery PX-001 with both parts; PO RECEIVING, task handed over to inspection")
    void utc01() {
        GoodsReceiptDetail result = create(delivery("PX-001"));

        assertThat(result.deliveryDocumentNo()).isEqualTo("PX-001");
        assertThat(result.arrivedAt()).isEqualTo(NOW);
        assertThat(lines(result)).containsExactly(tuple(PAD, 10, 10), tuple(OIL, 5, 5));
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVING);
        verify(warehouseTasks).handOverToInspection(staff, TaskType.SUPPLIER_RECEIVING, 30, 50);
        verify(auditLog).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("UTCID02 N: blank document number is kept as none, no duplicate check")
    void utc02() {
        GoodsReceiptDetail result = create(delivery("   "));

        assertThat(result.deliveryDocumentNo()).isNull();
        verify(receipts, never()).existsByPurchaseOrderIdAndDeliveryDocumentNoIgnoreCase(anyLong(), anyString());
    }

    @Test
    @DisplayName("UTCID03 N: part 11 fully received before, part 10 still 4 outstanding")
    void utc03() {
        outstanding.put(PAD, 4);
        outstanding.put(OIL, 0);

        GoodsReceiptDetail result = create(request("PX-002", TODAY, null, null, List.of(new DocumentLine(PAD, 4))));

        assertThat(result.deliveryDocumentNo()).isEqualTo("PX-002");
        assertThat(lines(result)).containsExactly(tuple(PAD, 4, 4));
    }

    @Test
    @DisplayName("UTCID04 B: arrived at exactly now (09:00)")
    void utc04() {
        GoodsReceiptDetail result = create(request("PX-001", TODAY, NOW, null, BOTH));

        assertThat(result.arrivedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("UTCID05 B: document dated tomorrow -> MSG138")
    void utc05() {
        assertRefused(request("PX-001", TODAY.plusDays(1), null, null, BOTH), HttpStatus.BAD_REQUEST, "MSG138");
        verify(orders, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("UTCID06 B: arrived one minute from now -> MSG138")
    void utc06() {
        assertRefused(request("PX-001", TODAY, NOW.plusMinutes(1), null, BOTH), HttpStatus.BAD_REQUEST, "MSG138");
    }

    @Test
    @DisplayName("UTCID07 A: PO 30 does not exist -> MSG131")
    void utc07() {
        when(orders.findByIdForUpdate(30L)).thenReturn(Optional.empty());

        assertRefused(delivery("PX-001"), HttpStatus.NOT_FOUND, "MSG131");
    }

    @Test
    @DisplayName("UTCID08 A: user 7 does not hold the receiving task -> MSG05")
    void utc08() {
        doThrow(new BusinessException(HttpStatus.FORBIDDEN, "MSG05")).when(warehouseTasks)
                .requireAssignee(staff, TaskType.SUPPLIER_RECEIVING, 30);

        assertRefused(delivery("PX-001"), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID09 A: PO 30 CANCELLED -> MSG29")
    void utc09() {
        with(order, "status", PurchaseOrderStatus.CANCELLED);

        assertRefused(delivery("PX-001"), HttpStatus.CONFLICT, "MSG29");
    }

    @Test
    @DisplayName("UTCID10 A: PO 30 RECEIVING with nothing outstanding -> MSG29")
    void utc10() {
        with(order, "status", PurchaseOrderStatus.RECEIVING);
        outstanding.put(PAD, 0);
        outstanding.put(OIL, 0);

        assertRefused(delivery("PX-001"), HttpStatus.CONFLICT, "MSG29");
    }

    @Test
    @DisplayName("UTCID11 A: document PX-001 already recorded for PO 30 -> MSG28")
    void utc11() {
        when(receipts.existsByPurchaseOrderIdAndDeliveryDocumentNoIgnoreCase(30L, "PX-001")).thenReturn(true);

        assertRefused(delivery("PX-001"), HttpStatus.CONFLICT, "MSG28");
    }

    @Test
    @DisplayName("UTCID12 A: part 10 twice on the document lines -> MSG277")
    void utc12() {
        assertRefused(request("PX-001", TODAY, null, null, List.of(new DocumentLine(PAD, 10),
                new DocumentLine(PAD, 2))), HttpStatus.BAD_REQUEST, "MSG277");
    }

    @Test
    @DisplayName("UTCID13 A: part 99 is not expected -> MSG277")
    void utc13() {
        assertRefused(request("PX-001", TODAY, null, null, List.of(new DocumentLine(PAD, 10),
                new DocumentLine(99L, 1))), HttpStatus.BAD_REQUEST, "MSG277");
    }

    @Test
    @DisplayName("UTCID14 A: discrepancy case 77 does not exist -> MSG133")
    void utc14() {
        when(discrepancies.findAllByIdForUpdate(any())).thenReturn(List.of());

        assertRefused(replacement(BOTH), HttpStatus.NOT_FOUND, "MSG133");
    }

    @Test
    @DisplayName("UTCID15 N: replacement delivery for case 77 while PO 30 is FULLY_RECEIVED")
    void utc15() {
        with(order, "status", PurchaseOrderStatus.FULLY_RECEIVED);

        GoodsReceiptDetail result = create(replacement(List.of(new DocumentLine(PAD, 3))));

        assertThat(result.replacement()).isTrue();
        assertThat(result.replacementForCaseIds()).containsExactly(77L);
        assertThat(lines(result)).containsExactly(tuple(PAD, 3, 3));
        assertThat(replacements).singleElement().satisfies(event -> {
            assertThat(event.getResolutionType()).isEqualTo(ResolutionType.REPLACEMENT_RECEIVED);
            assertThat(event.getQuantity()).isEqualTo(3);
        });
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
    }

    @Test
    @DisplayName("UTCID16 A: case 77 is a transfer discrepancy -> MSG133")
    void utc16() {
        case77 = withId(DiscrepancyCase.transfer(9, PAD, 3, NOW), 77);

        assertRefused(replacement(List.of(new DocumentLine(PAD, 3))), HttpStatus.NOT_FOUND, "MSG133");
    }

    @Test
    @DisplayName("UTCID17 A: GRN 40 of case 77 is not a delivery of PO 30 -> MSG133")
    void utc17() {
        when(receipts.findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(30L)).thenReturn(List.of());

        assertRefused(replacement(List.of(new DocumentLine(PAD, 3))), HttpStatus.NOT_FOUND, "MSG133");
    }

    @Test
    @DisplayName("UTCID18 A: case 77 already closed -> MSG141")
    void utc18() {
        case77.close(ClosureType.ALL_SETTLED, null, 1, NOW);

        assertRefused(replacement(List.of(new DocumentLine(PAD, 3))), HttpStatus.CONFLICT, "MSG141");
    }

    @Test
    @DisplayName("UTCID19 B: the 3 pads of case 77 are all on another replacement delivery -> MSG141")
    void utc19() {
        pendingOn77 = 3;

        assertRefused(replacement(List.of(new DocumentLine(PAD, 3))), HttpStatus.CONFLICT, "MSG141");
    }

    @Test
    @DisplayName("UTCID20 B: no document number, date, deliverer, plate or document lines")
    void utc20() {
        GoodsReceiptDetail result = create(new GoodsReceiptRequest(null, null, null, "  ", null, null, PHOTO, null));

        assertThat(result.deliveryDocumentNo()).isNull();
        assertThat(result.deliveryDocumentDate()).isNull();
        assertThat(result.delivererName()).isNull();
        assertThat(result.vehiclePlateNo()).isNull();
        assertThat(lines(result)).containsExactly(tuple(PAD, 0, 10), tuple(OIL, 0, 5));
    }

    @Test
    @DisplayName("UTCID21 A: PO 30 is still a draft -> MSG131")
    void utc21() {
        with(order, "status", PurchaseOrderStatus.DRAFT);

        assertRefused(delivery("PX-001"), HttpStatus.NOT_FOUND, "MSG131");
    }
}
