package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.dto.LotAdjustPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.transfer.dto.ReceiverMiscountRequest;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferDecisionRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferDecisionRequest.Decision;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.TransferDiscrepancyService;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TransferDiscrepancyServiceImpl implements TransferDiscrepancyService {

    private static final String ENTITY = "TRANSFER_ORDER";

    private final TransferOrderRepository orders;
    private final TransferOrderLineRepository lines;
    private final TransferReconciliation reconciliation;
    private final DiscrepancyCaseRepository cases;
    private final DiscrepancyResolutionEventRepository events;
    private final ProductRepository products;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final TransferOrderService transferOrders;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final StockBalanceRepository balances;
    private final InventoryPostingService posting;
    private final Clock clock;

    /** Decisions and results of orders before PM 23/11 that moved goods and cannot be undone by a correction. */
    private static final Set<ResolutionType> LEGACY_RESULTS = EnumSet.of(ResolutionType.RECEIVED_BACK_AT_SOURCE,
            ResolutionType.APPROVED_LOSS);

    /**
     * PM 23/11 case 1: the receiving store's BM, or the Owner, decides how much of a short part comes later and how
     * much is not to be replaced, at most what nobody decided on yet (MSG217). Both stay pending: a later receipt
     * settles the first, the closing moves the second back to the source.
     */
    @Transactional
    @Override
    public TransferOrderDetailResponse decide(AuthenticatedUser user, long transferOrderId,
                                              TransferDecisionRequest body) {
        if (body.decision() == Decision.RETURN_TO_SOURCE || body.decision() == Decision.LOSS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG345");
        }
        TransferOrder order = orders.findByIdForUpdate(transferOrderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        boolean destination = user.role() == Role.BRANCH_MANAGER
                && Objects.equals(user.storeId(), order.getDestStoreId());
        if (!destination && user.role() != Role.OWNER) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (order.getStatus() != TransferOrderStatus.RECONCILING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        Product part = products.findById(body.productId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57"));
        DiscrepancyCase shortage = orders.findCases(transferOrderId).stream()
                .filter(found -> found.getProductId().equals(body.productId()) && found.isOpen()).findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG216", part.getSku()));
        cases.findByIdForUpdate(shortage.getId()).orElseThrow();
        TransferReconciliation.Part figure = reconciliation.byProduct(transferOrderId,
                lines.findByTransferOrderIdOrderByProductId(transferOrderId)).get(part.getId());
        // A receipt already open for the part expects some of the shortage; a late-delivery decision covers it.
        long expected = lines.openReceiptQty(transferOrderId).stream()
                .filter(row -> row.getProductId() == part.getId()).mapToLong(row -> row.getQty()).sum();
        int open = figure == null ? 0 : Math.max(figure.shortage() - figure.rules().legacyClaims()
                - (int) Math.max(figure.rules().latePending(), expected), 0);
        if (body.quantity() > open) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG217", part.getSku(), open);
        }
        String reason = StringUtils.hasText(body.reason()) ? body.reason().strip() : null;
        LocalDateTime now = LocalDateTime.now(clock);
        events.save(body.decision() == Decision.LATE_DELIVERY
                ? DiscrepancyResolutionEvent.lateDeliveryDecision(shortage.getId(), body.quantity(), reason, user.id(), now)
                : DiscrepancyResolutionEvent.noReplacementDecision(shortage.getId(), body.quantity(), reason, user.id(),
                        now));
        Map<String, Object> decided = new LinkedHashMap<>();
        decided.put("product_id", body.productId());
        decided.put("decision", body.decision().name());
        decided.put("quantity", body.quantity());
        auditLog.save(AuditLog.record(ENTITY, transferOrderId, "TRANSFER_DISCREPANCY_DECIDED", user.id(), reason,
                null, decided, now));
        String message = body.decision() == Decision.LATE_DELIVERY
                ? "Phiếu chuyển kho #%d: %s x%d sẽ đến muộn, vui lòng ghi nhận khi hàng tới"
                : "Phiếu chuyển kho #%d: %s x%d không cần giao bù, sẽ cộng lại tồn kho gửi khi đóng phiếu";
        String text = message.formatted(transferOrderId, part.getSku(), body.quantity());
        notifications.notifyStoreManagers(order.getDestStoreId(), "TRANSFER_DISCREPANCY_DECIDED", text, ENTITY,
                transferOrderId);
        notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_DISCREPANCY_DECIDED", text, ENTITY,
                transferOrderId);
        if (user.role() != Role.OWNER) {
            notifications.notifyOwners("TRANSFER_DISCREPANCY_DECIDED", text, ENTITY, transferOrderId);
        }
        return transferOrders.detail(user, transferOrderId);
    }

    /**
     * PM 23/11 case 7: the Owner finds the receiving store counted the line wrong. The destination then holds exactly
     * what was dispatched and the source is unchanged: what is still in transit and what was written off as damaged
     * go into the destination's available stock, lot by lot (TRANSFER_RECEIPT_CORRECTED, sourced by the
     * RECEIVER_MISCOUNT event); the line's excess awaiting the Owner, phiếu nhận hàng giao sai awaiting the Owner and
     * pending late delivery / no replacement decisions are void. The staff's receipts keep what they recorded.
     * Refused (MSG344) once something of the line cannot be undone: a confirmed phiếu nhận hàng giao sai, accepted
     * excess, or a return or loss of an order before 23/11; and while a receipt of the part is open (MSG29).
     */
    @Transactional
    @Override
    public TransferOrderDetailResponse receiverMiscount(AuthenticatedUser user, long transferOrderId, long caseId,
                                                        ReceiverMiscountRequest body) {
        if (user.role() != Role.OWNER) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (body == null || !StringUtils.hasText(body.reason())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG343");
        }
        String reason = body.reason().strip();
        TransferOrder order = orders.findByIdForUpdate(transferOrderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (order.getStatus() != TransferOrderStatus.RECONCILING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        DiscrepancyCase found = cases.findByIdForUpdate(caseId)
                .filter(item -> Objects.equals(item.getTransferOrderId(), transferOrderId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG70"));
        long productId = found.getProductId();
        Product part = products.findById(productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57"));
        if (!found.isOpen()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG216", part.getSku());
        }
        if (lines.openReceiptQty(transferOrderId).stream()
                .anyMatch(row -> row.getProductId() == productId && row.getQty() > 0)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        TransferOrderLine line = lines.findByTransferOrderIdOrderByProductId(transferOrderId).stream()
                .filter(item -> item.getProductId() == productId).findFirst().orElseThrow();
        List<DiscrepancyResolutionEvent> history = orders.findEvents(List.of(caseId));
        List<GoodsReceiptNoteLine> received = receiptLines.findByGoodsReceiptNoteIdInOrderByIdAsc(
                        orders.findReceipts(transferOrderId).stream()
                                .filter(receipt -> receipt.getReceiptType() == ReceiptType.TRANSFER_IN)
                                .map(GoodsReceiptNote::getId).toList()).stream()
                .filter(item -> item.getProductId() == productId).toList();
        String blocked = null;
        // Review 25/11 (R-14): a hand-back still awaiting the source is cancelled below like a phiếu; one it confirmed
        // blocks.
        if (history.stream().anyMatch(event -> event.getStatus() == WrongItemReceiptStatus.CONFIRMED
                && event.getResolutionType() == ResolutionType.RECEIVED_AS_OTHER_PRODUCT)) {
            blocked = "phiếu nhận hàng giao sai đã được kho gửi xác nhận";
        } else if (history.stream().anyMatch(event -> event.getStatus() == WrongItemReceiptStatus.CONFIRMED
                && event.getResolutionType() == ResolutionType.WRONG_ITEM_RETURNED)) {
            blocked = "kho gửi đã xác nhận nhận lại hàng giao sai hoàn trả";
        } else if (received.stream().anyMatch(item -> item.getExcessDecision() == ExcessDecision.ACCEPTED)
                || line.getExtraDispatchedQty() > 0) {
            blocked = "hàng thừa đã được chấp nhận";
        } else if (history.stream().anyMatch(event -> LEGACY_RESULTS.contains(event.getResolutionType()))
                || reconciliation.pendingClaims(transferOrderId).getOrDefault(productId, 0L) > 0
                || lines.appliedLoss(transferOrderId).stream().anyMatch(row -> row.getProductId() == productId)) {
            blocked = "đã có hàng trả về kho gửi hoặc ghi mất";
        }
        if (blocked != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG344", part.getSku(), blocked);
        }

        balances.findByStoreForUpdate(order.getDestStoreId(), List.of(productId));
        LocalDateTime now = LocalDateTime.now(clock);
        int dispatched = line.getDispatchedQty() == null ? 0 : line.getDispatchedQty();
        DiscrepancyResolutionEvent correction = events.save(DiscrepancyResolutionEvent.done(caseId,
                ResolutionType.RECEIVER_MISCOUNT, dispatched, reason, user.id(), now));
        SourceRef source = SourceRef.discrepancyResolutionEvent(correction.getId());
        String note = "Owner sửa: bên nhận kiểm sai";
        // (a) what is still in transit, (b) what was written off as damaged: into the destination's available
        // stock. One plus per lot, as a movement is unique per type, source, lot and status.
        Map<Long, Integer> intoAvailable = new java.util.TreeMap<>();
        int inTransit = 0;
        for (var lot : orders.findTransitLots(transferOrderId, order.getDestStoreId(), productId)) {
            int quantity = Math.toIntExact(lot.getQuantity());
            posting.adjustLot(new LotAdjustPosting(order.getDestStoreId(), lot.getLotId(), StockStatus.IN_TRANSIT,
                    -quantity, MovementType.TRANSFER_RECEIPT_CORRECTED, source, user.id(), note));
            intoAvailable.merge(lot.getLotId(), quantity, Integer::sum);
            inTransit += quantity;
        }
        int damaged = 0;
        for (var lot : orders.findDamagedLots(transferOrderId, order.getDestStoreId(), productId)) {
            int quantity = Math.toIntExact(lot.getQuantity());
            if (quantity > 0) {
                intoAvailable.merge(lot.getLotId(), quantity, Integer::sum);
                damaged += quantity;
            }
        }
        intoAvailable.forEach((lotId, quantity) -> posting.adjustLot(new LotAdjustPosting(order.getDestStoreId(),
                lotId, StockStatus.AVAILABLE, quantity, MovementType.TRANSFER_RECEIPT_CORRECTED, source, user.id(),
                note)));
        // (c) excess, (d) phiếu nhận hàng giao sai awaiting the source, (e) pending decisions: void.
        int excess = 0;
        for (GoodsReceiptNoteLine item : received) {
            if (item.getExcessDecision() == ExcessDecision.AWAITING_OWNER) {
                excess += item.getExcessQty();
                item.decideExcess(ExcessDecision.REJECTED);
            }
        }
        List<DiscrepancyResolutionEvent> open = events.findOpenByCaseForUpdate(caseId);
        List<DiscrepancyResolutionEvent> voided = new ArrayList<>();
        int wrong = 0;
        for (DiscrepancyResolutionEvent event : open) {
            if (event.getStatus() == WrongItemReceiptStatus.AWAITING_SOURCE) {
                event.cancelWrongItemReceipt();
                wrong += event.getQuantity();
            } else if (event.getResolutionType() == ResolutionType.LATE_DELIVERY_RECEIVED
                    || event.getResolutionType() == ResolutionType.NO_REPLACEMENT) {
                voided.add(event);
            }
        }
        events.deleteAll(voided);
        found.close(ClosureType.CLOSED_BY_AGREEMENT, note + ": " + reason, user.id(), now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product_id", productId);
        data.put("dispatched_qty", dispatched);
        data.put("from_transit_qty", inTransit);
        data.put("damaged_restored_qty", damaged);
        data.put("excess_voided_qty", excess);
        data.put("wrong_item_receipts_cancelled_qty", wrong);
        data.put("decisions_voided", voided.stream().map(event -> Map.of("type", event.getResolutionType().name(),
                "quantity", event.getQuantity())).toList());
        auditLog.save(AuditLog.record(ENTITY, transferOrderId, "TRANSFER_RECEIVER_MISCOUNT", user.id(), reason, null,
                data, now));
        String text = ("Phiếu chuyển kho #%d: Owner sửa %s theo \"Bên nhận kiểm sai\" (%s). Kho nhận có đủ %d như "
                + "số đã xuất, kho gửi không đổi; thừa, giao sai và quyết định của dòng đã hủy.")
                .formatted(transferOrderId, part.getSku(), reason, dispatched);
        notifications.notifyStoreManagers(order.getDestStoreId(), "TRANSFER_RECEIVER_MISCOUNT", text, ENTITY,
                transferOrderId);
        notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_RECEIVER_MISCOUNT", text, ENTITY,
                transferOrderId);
        return transferOrders.detail(user, transferOrderId);
    }
}
