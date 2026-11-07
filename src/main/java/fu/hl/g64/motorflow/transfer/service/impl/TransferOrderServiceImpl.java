package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.inventory.service.InventoryService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderResponse;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.helper.RequestedParts;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferOrderServiceImpl implements TransferOrderService {

    /** audit_log.entity_type and notification.ref_type of a transfer order. */
    private static final String ENTITY = "TRANSFER_ORDER";

    private final TransferOrderRepository orders;
    private final TransferOrderLineRepository lines;
    private final ReplenishmentRequestRepository requests;
    private final ReplenishmentRequestLineRepository requestLines;
    private final DiscrepancyCaseRepository cases;
    private final StoreRepository stores;
    private final StoreScope storeScope;
    private final RequestedParts requestedParts;
    private final InventoryService inventory;
    private final UserAccountRepository users;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final Clock clock;

    // UC-71
    @Transactional
    @Override
    public TransferOrderResponse create(AuthenticatedUser user, TransferOrderCreateRequest body) {
        requireCoordinator(user);
        if (body.sourceStoreId().equals(body.destStoreId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG200");
        }
        activeStore(body.sourceStoreId(), false);
        Store destination = activeStore(body.destStoreId(), user.role() == Role.OWNER);
        Map<Long, Product> parts = requestedParts.requireActive(body.lines().stream()
                .map(TransferOrderCreateRequest.Line::productId).toList());
        body.lines().stream().filter(line -> line.makeupForCaseId() != null)
                .forEach(line -> checkMakeup(line, body.destStoreId(), parts.get(line.productId())));
        ReplenishmentRequest request = body.replenishmentRequestId() == null ? null : coveredRequest(body, parts);
        checkSellable(user, body, parts);

        LocalDateTime now = LocalDateTime.now(clock);
        TransferOrder order = orders.save(TransferOrder.create(body.replenishmentRequestId(), body.sourceStoreId(),
                body.destStoreId(), user.id(), now));
        List<TransferOrderLine> saved = lines.saveAll(body.lines().stream().map(line -> TransferOrderLine.create(
                order.getId(), line.productId(), line.plannedQty(), line.makeupForCaseId())).toList());
        if (request != null) {
            request.startCoordination();
        }
        auditLog.save(AuditLog.record(ENTITY, order.getId(), "TRANSFER_ORDER_CREATED", user.id(), null, null,
                Map.of("source_store_id", order.getSourceStoreId(), "dest_store_id", order.getDestStoreId(),
                        "lines", saved.stream().map(line -> Map.of("product_id", line.getProductId(),
                                "planned_qty", line.getPlannedQty())).toList()), now));
        String message = "Có phiếu chuyển kho #%d cần chuẩn bị hàng gửi tới %s"
                .formatted(order.getId(), destination.getStoreName());
        users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, order.getSourceStoreId(), AccountStatus.ACTIVE).stream()
                .filter(manager -> manager.getId() != user.id())
                .forEach(manager -> notifications.notify(manager.getId(), "TRANSFER_CREATED", message, ENTITY,
                        order.getId()));
        return new TransferOrderResponse(order.getId(), order.getStatus(), order.getReplenishmentRequestId(),
                order.getSourceStoreId(), order.getDestStoreId(), order.getCreatedBy(), order.getCreatedAt(),
                saved.stream().map(line -> new TransferOrderResponse.Line(line.getProductId(), line.getPlannedQty(),
                        line.getMakeupForCaseId())).toList());
    }

    /** BR-56: the Owner or the central warehouse Branch Manager. */
    private void requireCoordinator(AuthenticatedUser user) {
        if (!storeScope.seesEveryStore(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
    }

    /** BR-64: only the Owner may stock a store that has not opened yet. */
    private Store activeStore(long id, boolean plannedAllowed) {
        return stores.findById(id)
                .filter(store -> store.getStatus() == StoreStatus.ACTIVE
                        || (plannedAllowed && store.getStatus() == StoreStatus.PLANNED))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
    }

    /** BR-62: a make-up line replaces goods missing on another order to the same store, up to the shortage. */
    private void checkMakeup(TransferOrderCreateRequest.Line line, long destStoreId, Product part) {
        boolean valid = cases.findById(line.makeupForCaseId())
                .filter(found -> found.getSourceType() == SourceType.TRANSFER && found.isOpen()
                        && found.getProductId().equals(line.productId()) && line.plannedQty() <= found.getShortQty())
                .map(DiscrepancyCase::getTransferOrderId).flatMap(orders::findById)
                .filter(original -> original.getDestStoreId().equals(destStoreId)).isPresent();
        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG206", part.getSku());
        }
    }

    /**
     * UC-71 step 4, locked so that two orders for one request cannot both take what remains. Make-up lines stand in for
     * goods already planned, so they neither need nor use up the demand.
     */
    private ReplenishmentRequest coveredRequest(TransferOrderCreateRequest body, Map<Long, Product> parts) {
        ReplenishmentRequest request = requests.findByIdForUpdate(body.replenishmentRequestId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG95"));
        if (request.getStatus() == ReplenishmentRequestStatus.CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        if (!request.getRequestingStoreId().equals(body.destStoreId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG203");
        }
        Map<Long, ReplenishmentRequestLine> requested = requestLines
                .findByReplenishmentRequestIdOrderById(request.getId()).stream()
                .collect(Collectors.toMap(ReplenishmentRequestLine::getProductId, Function.identity()));
        Map<Long, Long> planned = lines.plannedByProduct(request.getId()).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.ProductQty::getProductId,
                        TransferOrderLineRepository.ProductQty::getQty));
        for (int i = 0; i < body.lines().size(); i++) {
            TransferOrderCreateRequest.Line line = body.lines().get(i);
            if (line.makeupForCaseId() != null) {
                continue;
            }
            ReplenishmentRequestLine wanted = requested.get(line.productId());
            if (wanted == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG204", i + 1);
            }
            long remaining = wanted.getRequestedQty() - wanted.getToPurchaseQty()
                    - planned.getOrDefault(line.productId(), 0L);
            if (line.plannedQty() > remaining) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG202", parts.get(line.productId()).getSku(),
                        Math.max(remaining, 0));
            }
        }
        return request;
    }

    /** UC-48: available minus expired; the source's stock is only checked here, never reserved. */
    private void checkSellable(AuthenticatedUser user, TransferOrderCreateRequest body, Map<Long, Product> parts) {
        Map<Long, Integer> sellable = inventory.availability(user, parts.keySet()).stream()
                .filter(row -> row.storeId() == body.sourceStoreId())
                .collect(Collectors.toMap(StockAvailabilityResponse::productId, StockAvailabilityResponse::sellableQty));
        for (TransferOrderCreateRequest.Line line : body.lines()) {
            int held = sellable.getOrDefault(line.productId(), 0);
            if (line.plannedQty() > held) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG201", parts.get(line.productId()).getSku(), held);
            }
        }
    }
}
