package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentRequestRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.helper.StoreScope;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TransferDiscrepancyServiceImpl implements TransferDiscrepancyService {

    private static final String ENTITY = "TRANSFER_ORDER";
    /** An adjustment that may still be applied; a rejected, cancelled or withdrawn one settles nothing. */
    private static final Set<AdjustmentRequestStatus> LIVE = EnumSet.of(AdjustmentRequestStatus.DRAFT,
            AdjustmentRequestStatus.PENDING, AdjustmentRequestStatus.APPROVED, AdjustmentRequestStatus.APPLIED);

    private final TransferOrderRepository orders;
    private final TransferOrderLineRepository lines;
    private final TransferReconciliation reconciliation;
    private final AdjustmentRequestRepository adjustments;
    private final DiscrepancyCaseRepository cases;
    private final DiscrepancyResolutionEventRepository events;
    private final ProductRepository products;
    private final StoreScope storeScope;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final TransferOrderService transferOrders;
    private final Clock clock;

    @Transactional
    @Override
    public TransferOrderDetailResponse decide(AuthenticatedUser user, long transferOrderId,
                                              TransferDecisionRequest body) {
        if (!storeScope.seesEveryStore(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        TransferOrder order = orders.findByIdForUpdate(transferOrderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (order.getStatus() != TransferOrderStatus.RECONCILING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        Product part = products.findById(body.productId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57"));
        DiscrepancyCase shortage = orders.findCases(transferOrderId).stream()
                .filter(found -> found.getProductId().equals(body.productId()) && found.isOpen()).findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG216", part.getSku()));
        int uncovered = reconciliation.uncovered(transferOrderId,
                lines.findByTransferOrderIdOrderByProductId(transferOrderId)).getOrDefault(body.productId(), 0);
        // A return decided but not opened yet may be given up for a late delivery or a loss; an open return may not.
        int releasable = body.decision() == Decision.RETURN_TO_SOURCE ? 0 : releasableReturn(transferOrderId, part.getId());
        if (body.quantity() > uncovered + releasable) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG217", part.getSku(), uncovered + releasable);
        }
        String reason = StringUtils.hasText(body.reason()) ? body.reason().strip() : null;
        LocalDateTime now = LocalDateTime.now(clock);
        int released = Math.max(body.quantity() - uncovered, 0);
        if (released > 0) {
            releaseReturnDecisions(transferOrderId, shortage, released);
        }
        if (body.decision() == Decision.LOSS) {
            linkLoss(order, shortage, part, body, reason, user, now);
        } else if (body.decision() == Decision.RETURN_TO_SOURCE) {
            // Pending until the return receipt is confirmed; the source can open a return only up to these (UC-76).
            cases.findByIdForUpdate(shortage.getId()).orElseThrow();
            events.save(DiscrepancyResolutionEvent.returnDecision(shortage.getId(), body.quantity(), reason, user.id(),
                    now));
        }
        Map<String, Object> decided = new LinkedHashMap<>();
        decided.put("product_id", body.productId());
        decided.put("decision", body.decision().name());
        decided.put("quantity", body.quantity());
        decided.put("adjustment_request_id", body.adjustmentRequestId());
        if (released > 0) {
            decided.put("released_return_qty", released);
        }
        // History only: what settles the quantity is the receipt or the applied adjustment, never this row.
        auditLog.save(AuditLog.record(ENTITY, transferOrderId, "TRANSFER_DISCREPANCY_DECIDED", user.id(), reason,
                null, decided, now));
        String message = switch (body.decision()) {
            case LATE_DELIVERY -> "Phiếu chuyển kho #%d: %s x%d sẽ đến muộn, vui lòng ghi nhận khi hàng tới";
            case RETURN_TO_SOURCE -> "Phiếu chuyển kho #%d: %s x%d được trả về kho nguồn";
            case LOSS -> "Phiếu chuyển kho #%d: %s x%d được ghi nhận mất hoặc hỏng, chờ Owner duyệt điều chỉnh";
        };
        String text = message.formatted(transferOrderId, part.getSku(), body.quantity());
        notifications.notifyStoreManagers(order.getDestStoreId(), "TRANSFER_DISCREPANCY_DECIDED", text, ENTITY,
                transferOrderId);
        if (body.decision() == Decision.RETURN_TO_SOURCE) {
            notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_DISCREPANCY_DECIDED", text, ENTITY,
                    transferOrderId);
        }
        return transferOrders.detail(user, transferOrderId);
    }

    /** Return decisions of the part that no return has been opened for yet (R-06). */
    private int releasableReturn(long transferOrderId, long productId) {
        long decided = lines.pendingReturnDecisions(transferOrderId).stream()
                .filter(row -> row.getProductId() == productId).mapToLong(TransferOrderLineRepository.ProductQty::getQty).sum();
        long opened = lines.openReturnQty(transferOrderId).stream()
                .filter(row -> row.getProductId() == productId).mapToLong(TransferOrderLineRepository.ProductQty::getQty).sum();
        return (int) Math.max(decided - opened, 0);
    }

    /**
     * Gives {@code quantity} back from the part's pending return decisions, newest first, so a return recorded as 0
     * does not hold units for ever. Lock order: order, case, events. A pending decision has no GRN, stock movement or
     * attachment, so it is deleted when released whole.
     */
    private void releaseReturnDecisions(long transferOrderId, DiscrepancyCase shortage, int quantity) {
        cases.findByIdForUpdate(shortage.getId()).orElseThrow();
        List<DiscrepancyResolutionEvent> pending = events.findPendingReturnDecisionsForUpdate(transferOrderId,
                shortage.getProductId());
        int left = quantity;
        for (int i = pending.size() - 1; i >= 0 && left > 0; i--) {
            DiscrepancyResolutionEvent decision = pending.get(i);
            if (decision.getQuantity() <= left) {
                left -= decision.getQuantity();
                events.delete(decision);
            } else {
                decision.reduceTo(decision.getQuantity() - left);
                left = 0;
            }
        }
    }

    /**
     * BR-66: the STOCK_LOSS request of this order writing exactly this quantity off transit at the destination, linked
     * once. Until it is applied the quantity stays unresolved but cannot be covered twice.
     */
    private void linkLoss(TransferOrder order, DiscrepancyCase shortage, Product part, TransferDecisionRequest body,
                          String reason, AuthenticatedUser user, LocalDateTime now) {
        if (body.adjustmentRequestId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG218");
        }
        boolean valid = adjustments.findById(body.adjustmentRequestId())
                .filter(found -> found.getAdjustmentType() == AdjustmentType.STOCK_LOSS
                        && order.getId().equals(found.getTransferOrderId()) && LIVE.contains(found.getStatus()))
                .isPresent()
                && orders.transitWriteOff(body.adjustmentRequestId(), order.getDestStoreId(), part.getId())
                == body.quantity()
                && !orders.lossLinked(body.adjustmentRequestId());
        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG219", body.adjustmentRequestId(), body.quantity(),
                    part.getSku());
        }
        events.save(DiscrepancyResolutionEvent.transferLoss(shortage.getId(), body.quantity(),
                body.adjustmentRequestId(), reason, user.id(), now));
    }
}
