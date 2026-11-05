package fu.hl.g64.motorflow.receiving.helper;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Progress;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail.Assignee;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail.Line;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptSummary;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the GRN list rows and the GRN detail as the signed-in user may see them (BR-72: prices for Owner only). */
@Component
@RequiredArgsConstructor
public class GoodsReceiptViews {

    private final PurchaseOrderRepository orders;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final DiscrepancyCaseRepository discrepancies;
    private final DiscrepancyResolutionEventRepository events;
    private final LotRepository lots;
    private final StockBalanceRepository stockBalances;
    private final ProductRepository products;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final WarehouseTaskRepository tasks;
    private final AttachmentService attachments;
    private final ReceivingLedger ledger;
    private final DiscrepancySettlement settlement;

    public List<GoodsReceiptSummary> summaries(List<GoodsReceiptNote> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> ids = page.stream().map(GoodsReceiptNote::getId).toList();
        Map<Long, PurchaseOrder> orderById = byId(orders.findAllById(page.stream()
                .map(GoodsReceiptNote::getPurchaseOrderId).collect(Collectors.toSet())), PurchaseOrder::getId);
        Map<Long, Supplier> supplierById = byId(suppliers.findAllById(orderById.values().stream()
                .map(PurchaseOrder::getSupplierId).collect(Collectors.toSet())), Supplier::getId);
        Map<Long, String> storeNames = stores.findAllById(orderById.values().stream().map(PurchaseOrder::getStoreId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, WarehouseTask> taskByReceipt = tasks.findOpenInspections(ids).stream()
                .collect(Collectors.toMap(WarehouseTask::getGoodsReceiptNoteId, Function.identity()));
        Map<Long, String> names = names(taskByReceipt.values().stream().map(WarehouseTask::getAssignedTo).toList());
        Map<Long, Long> lineCounts = counts(receiptLines.countByReceipt(ids));
        Map<Long, Long> openCases = counts(discrepancies.countOpenByReceipt(ids));
        Set<Long> excessAwaiting = new HashSet<>(receiptLines.findReceiptsAwaitingExcessDecision(ids));
        Set<Long> replacements = ledger.replacementReceiptIds(ids);

        return page.stream().map(receipt -> {
            PurchaseOrder order = orderById.get(receipt.getPurchaseOrderId());
            Supplier supplier = supplierById.get(order.getSupplierId());
            WarehouseTask task = taskByReceipt.get(receipt.getId());
            return new GoodsReceiptSummary(receipt.getId(), receipt.getStatus(),
                    replacements.contains(receipt.getId()), order.getId(), supplier.getId(),
                    supplier.getSupplierName(), order.getStoreId(), storeNames.get(order.getStoreId()),
                    receipt.getDeliveryDocumentNo(), receipt.getDeliveryDocumentDate(), receipt.getArrivedAt(),
                    task == null ? null : task.getAssignedTo(), task == null ? null : names.get(task.getAssignedTo()),
                    lineCounts.getOrDefault(receipt.getId(), 0L), excessAwaiting.contains(receipt.getId()),
                    openCases.getOrDefault(receipt.getId(), 0L), receipt.getSubmittedAt(), receipt.getConfirmedAt());
        }).toList();
    }

    public GoodsReceiptDetail detail(AuthenticatedUser user, GoodsReceiptNote receipt, PurchaseOrder order) {
        long id = receipt.getId();
        boolean owner = user.role() == Role.OWNER;
        boolean manager = user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId());
        boolean confirmed = receipt.getStatus() == GoodsReceiptNoteStatus.CONFIRMED;

        List<GoodsReceiptNoteLine> lines = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(id);
        List<DiscrepancyResolutionEvent> replacements =
                events.findByGoodsReceiptNoteIdAndResolutionTypeOrderByIdAsc(id, ResolutionType.REPLACEMENT_RECEIVED);
        List<Long> replacementFor = replacements.stream().map(DiscrepancyResolutionEvent::getDiscrepancyCaseId)
                .toList();
        Map<Long, Integer> expected = confirmed ? Map.of() : expected(order, replacements);

        Set<Long> productIds = lines.stream().map(GoodsReceiptNoteLine::getProductId).collect(Collectors.toSet());
        Map<Long, Product> parts = byId(products.findAllById(productIds), Product::getId);
        Map<Long, String> bins = productIds.isEmpty() ? Map.of()
                : stockBalances.findByStoreIdAndProductIdIn(order.getStoreId(), productIds).stream()
                .filter(balance -> balance.getBinLocation() != null)
                .collect(Collectors.toMap(StockBalance::getProductId, StockBalance::getBinLocation));
        Map<Long, Long> lotByLine = lines.isEmpty() ? Map.of()
                : lots.findByGoodsReceiptNoteLineIdIn(lines.stream().map(GoodsReceiptNoteLine::getId).toList())
                .stream().collect(Collectors.toMap(Lot::getGoodsReceiptNoteLineId, Lot::getId));
        WarehouseTask task = tasks.findOpenInspections(List.of(id)).stream().findFirst().orElse(null);
        Map<Long, String> names = names(Arrays.asList(receipt.getRecordedBy(), receipt.getConfirmedBy(),
                receipt.getExcessDecidedBy(), task == null ? null : task.getAssignedTo()));
        Supplier supplier = suppliers.findById(order.getSupplierId()).orElseThrow();
        String storeName = stores.findById(order.getStoreId()).map(Store::getStoreName).orElse(null);
        long openCases = counts(discrepancies.countOpenByReceipt(List.of(id))).getOrDefault(id, 0L);

        List<Line> rows = lines.stream().map(line -> {
            Product part = parts.get(line.getProductId());
            Integer expectedQty = confirmed ? null : expected.getOrDefault(line.getProductId(), 0);
            int toStock = confirmed ? line.acceptedQty() : Math.min(line.getPassedQty(), expectedQty);
            return new Line(line.getId(), line.getProductId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                    bins.get(line.getProductId()), expectedQty, line.getDocumentQty(), line.getDeliveredQty(),
                    line.getPassedQty(), line.getWrongItemQty(), line.getDamagedQty(), line.shortQty(), toStock,
                    confirmed ? line.getExcessQty() : line.getPassedQty() - toStock, line.getExcessDecision(),
                    owner ? line.getUnitPrice() : null, lotByLine.get(line.getId()), line.getManufacturerLotNo(),
                    line.getManufactureDate(), line.getExpiryDate());
        }).toList();

        boolean awaitingConfirmation = receipt.getStatus() == GoodsReceiptNoteStatus.AWAITING_CONFIRMATION;
        boolean holdsTask = task != null && task.getAssignedTo() == user.id();
        boolean canRecord = receipt.awaitsResult() && holdsTask && task.getStatus() == WarehouseTaskStatus.ASSIGNED;
        // BR-26: whoever recorded the result, a Branch Manager who took the inspection included, cannot confirm it.
        boolean canConfirm = manager && awaitingConfirmation && !holdsTask
                && !Objects.equals(receipt.getRecordedBy(), user.id());
        boolean canDecideExcess = owner && confirmed
                && lines.stream().anyMatch(line -> line.getExcessDecision() == ExcessDecision.AWAITING_OWNER);

        return new GoodsReceiptDetail(id, receipt.getReceiptType(), receipt.getStatus(), !replacements.isEmpty(),
                replacementFor, order.getId(), supplier.getId(), supplier.getSupplierCode(),
                supplier.getSupplierName(), order.getStoreId(), storeName, receipt.getDeliveryDocumentNo(),
                receipt.getDeliveryDocumentDate(), receipt.getDelivererName(), receipt.getVehiclePlateNo(),
                receipt.getArrivedAt(), task == null ? null : new Assignee(task.getAssignedTo(),
                names.get(task.getAssignedTo()), task.getStatus(), task.getAssignedAt()),
                receipt.getRecordedBy(), names.get(receipt.getRecordedBy()), receipt.getSubmittedAt(),
                receipt.getRecountReason(), receipt.getConfirmedBy(), names.get(receipt.getConfirmedBy()),
                receipt.getConfirmedAt(), receipt.getExcessDecidedBy(), names.get(receipt.getExcessDecidedBy()),
                receipt.getExcessDecidedAt(), openCases, canRecord, canConfirm, manager && awaitingConfirmation,
                canDecideExcess, rows, attachments.list(OwnerType.GOODS_RECEIPT_NOTE, id));
    }

    /**
     * What an unconfirmed GRN may still put into available stock, by part: what the PO still expects of it, or for a
     * replacement delivery what its cases still owe (UC-44 step 5 and Step 1_Supplier replacement GRN).
     */
    public Map<Long, Integer> expected(PurchaseOrder order, List<DiscrepancyResolutionEvent> replacements) {
        Map<Long, Integer> expected = new HashMap<>();
        if (replacements.isEmpty()) {
            ledger.of(order.getId()).parts().forEach((productId, part) -> expected.put(productId, part.outstandingQty()));
            return expected;
        }
        Map<Long, DiscrepancyCase> cases = byId(discrepancies.findAllById(replacements.stream()
                .map(DiscrepancyResolutionEvent::getDiscrepancyCaseId).toList()), DiscrepancyCase::getId);
        Map<Long, Progress> progress = settlement.progress(cases.keySet());
        for (DiscrepancyResolutionEvent pending : replacements) {
            DiscrepancyCase found = cases.get(pending.getDiscrepancyCaseId());
            int quantity = pending.isConfirmed() ? 0
                    : new DiscrepancySettlement.Standing(found, progress.get(found.getId())).expectedFrom(pending);
            expected.merge(found.getProductId(), quantity, Integer::sum);
        }
        return expected;
    }

    private Map<Long, String> names(Collection<Long> ids) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        // A HashMap, so that looking up a person who is not there yet (a null id) gives null.
        Map<Long, String> names = new HashMap<>();
        if (!wanted.isEmpty()) {
            users.findAllById(wanted).forEach(person -> names.put(person.getId(), person.getFullName()));
        }
        return names;
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    private static <T> Map<Long, T> byId(Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
