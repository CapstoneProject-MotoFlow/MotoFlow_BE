package fu.hl.g64.motorflow.discrepancy.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductIdentifierRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductVehicleModelRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyHandlingRequest;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Progress;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.discrepancy.service.impl.DiscrepancyServiceImpl;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.pricing.service.RetailPriceService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.helper.ReceivingLedger;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Report 5.1, sheet Discrepancy.saveHandling: DiscrepancyServiceImpl.saveHandling(handler, receiptId, request),
 * UTCID01-19. Warehouse ID = 2, PO ID = 30, GRN ID = 40 (confirmed supplier delivery); lot ID = 900 holds its part 10
 * line, lot ID = 901 its part 11 line. Case ID = 50: part 10 short 2, wrong 3, damaged 1, limit 6, replacement 2;
 * case ID = 51: part 11 damaged 2, limit 2.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscrepancySaveHandlingUnitTest {

    private static final long PAD = 10, OIL = 11;
    private static final String NOTE = "NCC đồng ý";

    @Mock DiscrepancyCaseRepository discrepancies;
    @Mock DiscrepancyResolutionEventRepository events;
    @Mock GoodsReceiptNoteRepository receipts;
    @Mock GoodsReceiptNoteLineRepository receiptLines;
    @Mock PurchaseOrderRepository orders;
    @Mock SupplierRepository suppliers;
    @Mock StoreRepository stores;
    @Mock ProductRepository products;
    @Mock UserAccountRepository users;
    @Mock LotRepository lots;
    @Mock InventoryPostingService posting;
    @Mock AttachmentService attachments;
    @Mock AuditLogRepository auditLog;
    @Mock ReceivingLedger ledger;
    @Mock DiscrepancySettlement settlement;
    @Mock ProductIdentifierRepository identifiers;
    @Mock ProductVehicleModelRepository vehicles;
    @Mock RetailPriceService retailPrices;
    @Mock PricingPolicyRepository pricingPolicies;
    @Mock StockBalanceRepository balances;
    @Mock NotificationService notifications;

    private DiscrepancyServiceImpl service;
    private final AuthenticatedUser manager = auth(5, Role.BRANCH_MANAGER, 2L);
    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private GoodsReceiptNote grn;
    private DiscrepancyCase pad, oil;
    private final Map<Long, Progress> progress = new HashMap<>();
    private final List<Lot> grnLots = new ArrayList<>();

    @BeforeEach
    void data() {
        service = new DiscrepancyServiceImpl(discrepancies, events, receipts, receiptLines, orders, suppliers, stores,
                products, users, lots, posting, attachments, auditLog, ledger, settlement, identifiers, vehicles,
                retailPrices, pricingPolicies, balances, notifications, CLOCK);
        grn = withId(GoodsReceiptNote.supplierDelivery(30, "PX-001", NOW.toLocalDate(), null, null,
                NOW.minusDays(1)), 40);
        PurchaseOrder order = withId(PurchaseOrder.create(3, 2, null, null, 1, NOW.minusDays(3)), 30);
        pad = withId(DiscrepancyCase.supplier(40, PAD, 2, 3, 1, 6, NOW.minusDays(1)), 50);
        oil = withId(DiscrepancyCase.supplier(40, OIL, 0, 0, 2, 2, NOW.minusDays(1)), 51);
        grnLots.add(withId(Lot.create(PAD, LotSourceType.RECEIPT, 1L, null, 3L, 2L, NOW.toLocalDate(), 60000, null,
                null, null), 900));
        grnLots.add(withId(Lot.create(OIL, LotSourceType.RECEIPT, 2L, null, 3L, 2L, NOW.toLocalDate(), 70000, null,
                null, null), 901));

        when(receipts.findById(40L)).thenAnswer(inv -> Optional.of(grn));
        when(receipts.findAllById(any())).thenAnswer(inv -> List.of(grn));
        when(orders.findById(30L)).thenReturn(Optional.of(order));
        when(orders.findAllById(any())).thenReturn(List.of(order));
        when(suppliers.findAllById(any())).thenReturn(List.of(withId(Supplier.create("NCC-03", "Minh Phát", null,
                null, null, null, null, NOW), 3)));
        when(stores.findAllById(any())).thenReturn(List.of(store(2, StoreStatus.ACTIVE, false)));
        when(products.findAllById(any())).thenReturn(List.of(product(PAD, "MA-PHANH-01", ProductStatus.ACTIVE),
                product(OIL, "NHOT-01", ProductStatus.ACTIVE)));
        when(discrepancies.findByReceiptForUpdate(40L)).thenAnswer(inv -> List.of(pad, oil));
        when(settlement.progress(anyCollection())).thenAnswer(inv -> {
            Map<Long, Progress> found = new HashMap<>();
            inv.<Collection<Long>>getArgument(0)
                    .forEach(id -> found.put(id, progress.getOrDefault(id, Progress.NONE)));
            return found;
        });
        when(receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(40L)).thenReturn(List.of(
                withId(GoodsReceiptNoteLine.expected(40, PAD, 60000L, 10), 1),
                withId(GoodsReceiptNoteLine.expected(40, OIL, 70000L, 5), 2)));
        when(lots.findByGoodsReceiptNoteLineIdIn(any())).thenAnswer(inv -> grnLots.stream()
                .filter(lot -> inv.<List<Long>>getArgument(0).contains(lot.getGoodsReceiptNoteLineId())).toList());
        long[] nextEvent = {700};
        when(events.save(any())).thenAnswer(inv -> withId(inv.getArgument(0, DiscrepancyResolutionEvent.class),
                nextEvent[0]++));
    }

    private static DiscrepancyHandlingRequest.Line line(long caseId, int returnQty, Integer replacementQty) {
        return new DiscrepancyHandlingRequest.Line(caseId, returnQty, replacementQty);
    }

    private static DiscrepancyHandlingRequest request(String note, DiscrepancyHandlingRequest.Line... lines) {
        return new DiscrepancyHandlingRequest(List.of(lines), note);
    }

    private static DiscrepancyHandlingRequest request(DiscrepancyHandlingRequest.Line... lines) {
        return request(NOTE, lines);
    }

    private static DiscrepancyCaseResponse row(List<DiscrepancyCaseResponse> rows, long caseId) {
        return rows.stream().filter(r -> r.id() == caseId).findFirst().orElseThrow();
    }

    /** Case 50 was agreed before: return 3 (the 3 wrong pads held), replacement 5. */
    private void agreedBefore() {
        pad.agree(3, 5);
    }

    private List<MovePosting> moves() {
        ArgumentCaptor<MovePosting> moved = ArgumentCaptor.forClass(MovePosting.class);
        verify(posting, atLeast(0)).move(moved.capture());
        return moved.getAllValues();
    }

    private List<AuditLog> logs() {
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLog, atLeast(0)).save(saved.capture());
        return saved.getAllValues();
    }

    private void assertRefused(AuthenticatedUser user, DiscrepancyHandlingRequest request, HttpStatus status,
                               String code) {
        assertBusiness(() -> service.saveHandling(user, 40, request), status, code);
        verify(posting, never()).move(any());
        verify(auditLog, never()).save(any());
    }

    private static void assertMove(MovePosting move, StockStatus from, StockStatus to, int quantity, long lotId,
                                   MovementType type) {
        assertThat(move.fromStatus()).isEqualTo(from);
        assertThat(move.toStatus()).isEqualTo(to);
        assertThat(move.quantity()).isEqualTo(quantity);
        assertThat(move.lots()).containsExactly(new LotQuantity(lotId, quantity));
        assertThat(move.movementType()).isEqualTo(type);
    }

    @Test
    @DisplayName("UTCID01 N: return 3 of case 50 -> replacement 5, 3 wrong pads held in lot 900")
    void utc01() {
        List<DiscrepancyCaseResponse> rows = service.saveHandling(manager, 40, request(line(50, 3, null)));

        assertThat(rows).hasSize(2);
        assertThat(row(rows, 50).returnRequiredQty()).isEqualTo(3);
        assertThat(row(rows, 50).replacementRequiredQty()).isEqualTo(5);
        assertThat(row(rows, 51).returnRequiredQty()).isZero();
        assertThat(row(rows, 51).replacementRequiredQty()).isZero();
        assertThat(moves()).singleElement().satisfies(m -> assertMove(m, StockStatus.WRONG_ITEM,
                StockStatus.AWAITING_SUPPLIER_RETURN, 3, 900, MovementType.RETURN_HOLD));
        assertThat(logs()).singleElement().satisfies(log -> assertThat(log.getReason()).isEqualTo(NOTE));
    }

    @Test
    @DisplayName("UTCID02 N: return 4 -> replacement 6; 3 wrong then 1 damaged held")
    void utc02() {
        DiscrepancyCaseResponse found = row(service.saveHandling(manager, 40, request(line(50, 4, null))), 50);

        assertThat(found.returnRequiredQty()).isEqualTo(4);
        assertThat(found.replacementRequiredQty()).isEqualTo(6);
        List<MovePosting> moves = moves();
        assertThat(moves).hasSize(2);
        assertMove(moves.get(0), StockStatus.WRONG_ITEM, StockStatus.AWAITING_SUPPLIER_RETURN, 3, 900,
                MovementType.RETURN_HOLD);
        assertMove(moves.get(1), StockStatus.DAMAGED, StockStatus.AWAITING_SUPPLIER_RETURN, 1, 900,
                MovementType.RETURN_HOLD);
    }

    @Test
    @DisplayName("UTCID03 N: nothing goes back, replacement 4")
    void utc03() {
        DiscrepancyCaseResponse found = row(service.saveHandling(manager, 40, request(line(50, 0, 4))), 50);

        assertThat(found.returnRequiredQty()).isZero();
        assertThat(found.replacementRequiredQty()).isEqualTo(4);
        verify(posting, never()).move(any());
        assertThat(logs()).hasSize(1);
    }

    @Test
    @DisplayName("UTCID04 N: Owner, return 1 of case 50 and 2 of case 51")
    void utc04() {
        List<DiscrepancyCaseResponse> rows = service.saveHandling(owner, 40, request(line(50, 1, null),
                line(51, 2, null)));

        assertThat(row(rows, 50).returnRequiredQty()).isEqualTo(1);
        assertThat(row(rows, 51).returnRequiredQty()).isEqualTo(2);
        List<MovePosting> moves = moves();
        assertThat(moves).hasSize(2);
        assertMove(moves.get(0), StockStatus.WRONG_ITEM, StockStatus.AWAITING_SUPPLIER_RETURN, 1, 900,
                MovementType.RETURN_HOLD);
        assertMove(moves.get(1), StockStatus.DAMAGED, StockStatus.AWAITING_SUPPLIER_RETURN, 2, 901,
                MovementType.RETURN_HOLD);
        assertThat(logs()).hasSize(2);
    }

    @Test
    @DisplayName("UTCID05 B: return 0 and replacement 2, what case 50 already has -> nothing saved")
    void utc05() {
        DiscrepancyCaseResponse found = row(service.saveHandling(manager, 40, request(line(50, 0, 2))), 50);

        assertThat(found.returnRequiredQty()).isZero();
        assertThat(found.replacementRequiredQty()).isEqualTo(2);
        verify(posting, never()).move(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    @DisplayName("UTCID06 B: return 4 (= held), replacement 6 (= limit)")
    void utc06() {
        DiscrepancyCaseResponse found = row(service.saveHandling(manager, 40, request(line(50, 4, 6))), 50);

        assertThat(found.returnRequiredQty()).isEqualTo(4);
        assertThat(found.replacementRequiredQty()).isEqualTo(6);
        assertThat(moves()).hasSize(2);
    }

    @Test
    @DisplayName("UTCID07 A: GRN 40 is a transfer receipt -> MSG132")
    void utc07() {
        grn = withId(GoodsReceiptNote.transfer(ReceiptType.TRANSFER_IN, 80, NOW.minusDays(1)), 40);

        assertRefused(manager, request(line(50, 1, null)), HttpStatus.NOT_FOUND, "MSG132");
    }

    @Test
    @DisplayName("UTCID08 A: Branch Manager 6 of store 9 -> MSG05")
    void utc08() {
        assertRefused(auth(6, Role.BRANCH_MANAGER, 9L), request(line(50, 1, null)), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID09 A: case 50 given twice -> MSG136 for line 2")
    void utc09() {
        assertRefused(manager, request(line(50, 1, null), line(50, 2, null)), HttpStatus.BAD_REQUEST, "MSG136");
    }

    @Test
    @DisplayName("UTCID10 A: case 51 is CLOSED -> MSG29")
    void utc10() {
        with(oil, "status", DiscrepancyCaseStatus.CLOSED);

        assertRefused(manager, request(line(51, 2, null)), HttpStatus.CONFLICT, "MSG29");
    }

    @Test
    @DisplayName("UTCID11 A: return 5, above the 4 held -> MSG35")
    void utc11() {
        assertRefused(manager, request(line(50, 5, null)), HttpStatus.BAD_REQUEST, "MSG35");
    }

    @Test
    @DisplayName("UTCID12 N: agreed 3/5 before, return lowered to 2 -> 1 wrong pad released")
    void utc12() {
        agreedBefore();

        DiscrepancyCaseResponse found = row(service.saveHandling(manager, 40, request(line(50, 2, null))), 50);

        assertThat(found.returnRequiredQty()).isEqualTo(2);
        assertThat(found.replacementRequiredQty()).isEqualTo(5);
        assertThat(moves()).singleElement().satisfies(m -> assertMove(m, StockStatus.AWAITING_SUPPLIER_RETURN,
                StockStatus.WRONG_ITEM, 1, 900, MovementType.RETURN_RELEASE));
    }

    @Test
    @DisplayName("UTCID13 A: replacement 7, above the limit 6 -> MSG35")
    void utc13() {
        assertRefused(manager, request(line(50, 0, 7)), HttpStatus.BAD_REQUEST, "MSG35");
    }

    @Test
    @DisplayName("UTCID14 A: replacement 1, below the 2 short -> MSG193")
    void utc14() {
        assertRefused(manager, request(line(50, 0, 1)), HttpStatus.BAD_REQUEST, "MSG193");
    }

    @Test
    @DisplayName("UTCID15 A: 4 pads already on a replacement delivery, replacement 3 -> MSG193")
    void utc15() {
        progress.put(50L, new Progress(0, 0, 0, 0, 4, null, 0, 0));

        assertRefused(manager, request(line(50, 0, 3)), HttpStatus.BAD_REQUEST, "MSG193");
    }

    @Test
    @DisplayName("UTCID16 A: agreed 3/5, 2 pads went back, return lowered to 1 -> MSG134")
    void utc16() {
        agreedBefore();
        progress.put(50L, new Progress(2, 0, 0, 0, 0, null, 0, 0));

        assertRefused(manager, request(line(50, 1, null)), HttpStatus.BAD_REQUEST, "MSG134");
    }

    @Test
    @DisplayName("UTCID17 A: case 99 is not a case of GRN 40 -> MSG136")
    void utc17() {
        assertRefused(manager, request(line(99, 1, null)), HttpStatus.BAD_REQUEST, "MSG136");
    }

    @Test
    @DisplayName("UTCID18 B: a blank note -> the agreement is saved with no note")
    void utc18() {
        DiscrepancyCaseResponse found = row(service.saveHandling(manager, 40, request("   ", line(50, 0, 4))), 50);

        assertThat(found.returnRequiredQty()).isZero();
        assertThat(found.replacementRequiredQty()).isEqualTo(4);
        assertThat(logs()).singleElement().satisfies(log -> assertThat(log.getReason()).isNull());
    }

    @Test
    @DisplayName("UTCID19 A: the pad line of GRN 40 has no lot -> IllegalStateException")
    void utc19() {
        grnLots.removeIf(lot -> lot.getProductId() == PAD);

        assertThatThrownBy(() -> service.saveHandling(manager, 40, request(line(50, 3, null))))
                .isInstanceOf(IllegalStateException.class).hasMessage("no lot for part 10 on GRN 40");
        verify(posting, never()).move(any());
        verify(auditLog, never()).save(any());
        verify(settlement, times(0)).closeIfSettled(any(), anyLong(), any());
    }
}
