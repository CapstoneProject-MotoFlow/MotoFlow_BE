package fu.hl.g64.motorflow.receiving.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderReceiving;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
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

/** Report 5.1, sheet GoodsReceipt.confirm: GoodsReceiptServiceImpl.confirm(manager, grnId), UTCID01-12. */
class GoodsReceiptConfirmUnitTest extends GoodsReceiptUnitTestBase {

    private GoodsReceiptNote grn;
    private DiscrepancyCase case77;

    /**
     * PO 30 RECEIVING, still expects part 10 x 10 and part 11 x 5; GRN 50 AWAITING_CONFIRMATION, result recorded by
     * user 7: part 10 delivered 10 passed 10, part 11 delivered 5 passed 5.
     */
    @BeforeEach
    void data() {
        with(order, "status", PurchaseOrderStatus.RECEIVING);
        grn = withId(GoodsReceiptNote.supplierDelivery(30, "PX-001", NOW.toLocalDate(), null, null, NOW.withHour(8)),
                50);
        grnLines.add(recorded(1, PAD, 60000, 10, 10, 0, 0));
        grnLines.add(recorded(2, OIL, 70000, 5, 5, 0, 0));
        grn.submitResult(7, null, null, NOW.withHour(8).plusMinutes(30));
        when(receipts.findPurchaseOrderIdOf(anyLong())).thenReturn(Optional.empty());
        when(receipts.findPurchaseOrderIdOf(50L)).thenReturn(Optional.of(30L));
        when(orders.findByIdForUpdate(30L)).thenAnswer(inv -> Optional.of(order));
        when(receipts.findByIdForUpdate(50L)).thenAnswer(inv -> Optional.of(grn));
        // Posting takes the AVAILABLE quantity off what the PO still expects, as the real ledger would read it.
        when(posting.receiveNewLot(any())).thenAnswer(inv -> {
            NewLotPosting lot = inv.getArgument(0);
            outstanding.merge(lot.productId(), -lot.quantities().getOrDefault(StockStatus.AVAILABLE, 0), Integer::sum);
            return null;
        });
        case77 = withId(DiscrepancyCase.supplier(40, PAD, 3, 0, 0, 3, NOW.minusDays(1)), 77);
        when(discrepancies.findAllByIdForUpdate(any())).thenAnswer(inv -> List.of(case77));
        when(discrepancies.findAllById(any())).thenAnswer(inv -> List.of(case77));
    }

    private static GoodsReceiptNoteLine recorded(long id, long productId, long price, int document, int delivered,
                                                 int wrong, int damaged) {
        GoodsReceiptNoteLine line = withId(GoodsReceiptNoteLine.expected(50, productId, price, document), id);
        line.record(document, delivered, wrong, damaged, null, null, null);
        return line;
    }

    /** GRN 50 is a replacement delivery for case 77 (part 10 short 3) and brought {@code brought} pads. */
    private void replacementBringing(int brought) {
        grnLines.clear();
        grnLines.add(recorded(1, PAD, 60000, 3, brought, 0, 0));
        replacements.add(withId(DiscrepancyResolutionEvent.pending(77, ResolutionType.REPLACEMENT_RECEIVED, 3, 50L,
                null, 7, NOW.withHour(8)), 900));
    }

    private GoodsReceiptDetail confirm() {
        GoodsReceiptDetail result = service.confirm(manager, 50);
        assertThat(result.id()).isEqualTo(50);
        assertThat(result.status()).isEqualTo(GoodsReceiptNoteStatus.CONFIRMED);
        return result;
    }

    private static List<org.assertj.core.groups.Tuple> stocked(GoodsReceiptDetail result) {
        return result.lines().stream().map(l -> tuple(l.productId(), l.toStockQty())).toList();
    }

    private void assertRefused(AuthenticatedUser user, HttpStatus status, String code) {
        assertBusiness(() -> service.confirm(user, 50), status, code);
        verify(posting, never()).receiveNewLot(any());
        verify(warehouseTasks, never()).confirmResult(any(), any(), anyLong());
    }

    @Test
    @DisplayName("UTCID01 N: everything passed -> 2 lots, GRN CONFIRMED, PO FULLY_RECEIVED, no case")
    void utc01() {
        GoodsReceiptDetail result = confirm();

        assertThat(result.confirmedById()).isEqualTo(5L);
        assertThat(result.openDiscrepancies()).isZero();
        assertThat(stocked(result)).containsExactly(tuple(PAD, 10), tuple(OIL, 5));
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
        ArgumentCaptor<NewLotPosting> lots = ArgumentCaptor.forClass(NewLotPosting.class);
        verify(posting, org.mockito.Mockito.times(2)).receiveNewLot(lots.capture());
        assertThat(lots.getAllValues()).extracting(NewLotPosting::productId, NewLotPosting::purchaseUnitPrice)
                .containsExactly(tuple(PAD, 60000L), tuple(OIL, 70000L));
        verify(warehouseTasks).confirmResult(manager, TaskType.INSPECTION, 50);
        verify(warehouseTasks).cancelForDocument(TaskType.SUPPLIER_RECEIVING, 30);
        assertThat(openedCases).isEmpty();
    }

    @Test
    @DisplayName("UTCID02 N: part 10 short 2, part 11 one damaged -> 2 cases, PO stays RECEIVING")
    void utc02() {
        grnLines.set(0, recorded(1, PAD, 60000, 10, 8, 0, 0));
        grnLines.set(1, recorded(2, OIL, 70000, 5, 5, 0, 1));
        when(purchaseOrderReceiving.of(any())).thenReturn(new PurchaseOrderReceiving.Openness(true, false, null,
                List.of()));

        GoodsReceiptDetail result = confirm();

        assertThat(result.openDiscrepancies()).isEqualTo(2);
        assertThat(stocked(result)).containsExactly(tuple(PAD, 8), tuple(OIL, 4));
        assertThat(result.lines().get(1).damagedQty()).isEqualTo(1);
        assertThat(openedCases).extracting(DiscrepancyCase::getProductId, DiscrepancyCase::getShortQty,
                DiscrepancyCase::getDamagedQty).containsExactly(tuple(PAD, 2, 0), tuple(OIL, 0, 1));
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVING);
        verify(notifications).notifyOwners(eq("SUPPLIER_DISCREPANCY_OPENED"), anyString(), anyString(), eq(50L));
        verify(warehouseTasks, never()).cancelForDocument(any(), anyLong());
    }

    @Test
    @DisplayName("UTCID03 B: part 10 delivered and passed 12 -> 10 to stock, excess 2 waits for Owner")
    void utc03() {
        grnLines.set(0, recorded(1, PAD, 60000, 10, 12, 0, 0));

        GoodsReceiptDetail result = confirm();

        assertThat(result.openDiscrepancies()).isZero();
        assertThat(result.lines().get(0).toStockQty()).isEqualTo(10);
        assertThat(result.lines().get(0).excessQty()).isEqualTo(2);
        verify(notifications).notifyOwners(eq("EXCESS_AWAITING_DECISION"), anyString(), anyString(), eq(50L));
    }

    @Test
    @DisplayName("UTCID04 B: part 11 not delivered -> only the part 10 lot, case part 11 short 5")
    void utc04() {
        grnLines.set(1, recorded(2, OIL, 70000, 5, 0, 0, 0));

        GoodsReceiptDetail result = confirm();

        assertThat(result.openDiscrepancies()).isEqualTo(1);
        assertThat(result.lines().get(0).toStockQty()).isEqualTo(10);
        assertThat(result.lines().get(1).deliveredQty()).isZero();
        assertThat(result.lines().get(1).shortQty()).isEqualTo(5);
        verify(posting).receiveNewLot(any());
        assertThat(openedCases).singleElement().satisfies(c -> {
            assertThat(c.getProductId()).isEqualTo(OIL);
            assertThat(c.getShortQty()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("UTCID05 A: GRN 50 does not exist -> MSG132")
    void utc05() {
        when(receipts.findPurchaseOrderIdOf(50L)).thenReturn(Optional.empty());

        assertRefused(manager, HttpStatus.NOT_FOUND, "MSG132");
    }

    @Test
    @DisplayName("UTCID06 A: Branch Manager of store 3 -> MSG05")
    void utc06() {
        assertRefused(auth(6, Role.BRANCH_MANAGER, 3L), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID07 A: Owner -> MSG05")
    void utc07() {
        assertRefused(auth(1, Role.OWNER, null), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID08 A: GRN 50 AWAITING_INSPECTION -> MSG29")
    void utc08() {
        with(grn, "status", GoodsReceiptNoteStatus.AWAITING_INSPECTION);

        assertRefused(manager, HttpStatus.CONFLICT, "MSG29");
    }

    @Test
    @DisplayName("UTCID09 N: the manager confirms the result they recorded themself")
    void utc09() {
        with(grn, "recordedBy", 5L);

        GoodsReceiptDetail result = confirm();

        assertThat(result.recordedById()).isEqualTo(5L);
        assertThat(result.confirmedById()).isEqualTo(5L);
        verify(posting, org.mockito.Mockito.times(2)).receiveNewLot(any());
    }

    @Test
    @DisplayName("UTCID10 A: GRN 50 already CONFIRMED -> MSG29")
    void utc10() {
        with(grn, "status", GoodsReceiptNoteStatus.CONFIRMED);

        assertRefused(manager, HttpStatus.CONFLICT, "MSG29");
    }

    @Test
    @DisplayName("UTCID11 N: replacement delivery brought the 3 pads of case 77")
    void utc11() {
        replacementBringing(3);

        GoodsReceiptDetail result = confirm();

        assertThat(result.replacement()).isTrue();
        assertThat(result.openDiscrepancies()).isZero();
        assertThat(stocked(result)).containsExactly(tuple(PAD, 3));
        assertThat(openedCases).isEmpty();
        assertThat(replacements.get(0).isConfirmed()).isTrue();
        assertThat(replacements.get(0).getQuantity()).isEqualTo(3);
        verify(settlement).closeIfSettled(eq(case77), eq(5L), any());
    }

    @Test
    @DisplayName("UTCID12 B: replacement delivery brought only 1 pad -> confirmed with 1, no new case")
    void utc12() {
        replacementBringing(1);

        GoodsReceiptDetail result = confirm();

        assertThat(result.replacement()).isTrue();
        assertThat(result.openDiscrepancies()).isZero();
        assertThat(stocked(result)).containsExactly(tuple(PAD, 1));
        assertThat(openedCases).isEmpty();
        assertThat(replacements.get(0).getQuantity()).isEqualTo(1);
    }
}
