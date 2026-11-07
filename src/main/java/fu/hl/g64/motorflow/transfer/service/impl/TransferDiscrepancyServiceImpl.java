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
        if (body.quantity() > uncovered) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG217", part.getSku(), uncovered);
        }
        String reason = StringUtils.hasText(body.reason()) ? body.reason().strip() : null;
        LocalDateTime now = LocalDateTime.now(clock);
        if (body.decision() == Decision.LOSS) {
            linkLoss(order, shortage, part, body, reason, user, now);
        }
        Map<String, Object> decided = new LinkedHashMap<>();
        decided.put("product_id", body.productId());
        decided.put("decision", body.decision().name());
        decided.put("quantity", body.quantity());
        decided.put("adjustment_request_id", body.adjustmentRequestId());
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
