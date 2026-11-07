package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
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
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse.Person;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderRowResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderSearch;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.helper.RequestedParts;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
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
    private final TransferReconciliation reconciliation;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final UserAccountRepository users;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final Clock clock;

    // UC-71
    @Transactional
    @Override
    public TransferOrderDetailResponse create(AuthenticatedUser user, TransferOrderCreateRequest body) {
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
        return detail(user, order);
    }

    // UC-72
    @Transactional(readOnly = true)
    @Override
    public PageResponse<TransferOrderRowResponse> list(AuthenticatedUser user, TransferOrderSearch search, int page,
                                                       int size) {
        Long staffId = user.role() == Role.STAFF ? user.id() : null;
        Long storeId = staffId == null && !storeScope.seesEveryStore(user) ? user.storeId() : null;
        Page<TransferOrderRepository.OrderRow> found = orders.search(search.status(), search.sourceStoreId(),
                search.destStoreId(), search.replenishmentRequestId(), storeId, staffId, search.from(), search.to(),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
        return PageResponse.of(found, found.stream().map(row -> {
            TransferOrder order = row.getTransferOrder();
            return new TransferOrderRowResponse(order.getId(), order.getStatus(), order.getSourceStoreId(),
                    row.getSourceStoreName(), order.getDestStoreId(), row.getDestStoreName(),
                    order.getReplenishmentRequestId(), row.getLineCount(), row.getTotalPlannedQty(),
                    row.getCreatedByName(), order.getCreatedAt(), order.getDispatchedAt(), order.getClosedAt());
        }).toList());
    }

    // UC-73
    @Transactional(readOnly = true)
    @Override
    public TransferOrderDetailResponse detail(AuthenticatedUser user, long id) {
        TransferOrder order = orders.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        boolean allowed = user.role() == Role.STAFF ? orders.staffWorksOn(id, user.id())
                : storeScope.seesEveryStore(user) || Objects.equals(user.storeId(), order.getSourceStoreId())
                || Objects.equals(user.storeId(), order.getDestStoreId());
        if (!allowed) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return detail(user, order);
    }

    private TransferOrderDetailResponse detail(AuthenticatedUser user, TransferOrder order) {
        long id = order.getId();
        boolean staff = user.role() == Role.STAFF;
        boolean owner = user.role() == Role.OWNER;
        List<TransferOrderLine> orderLines = lines.findByTransferOrderIdOrderByProductId(id);
        Map<Long, Product> parts = products.findAllById(orderLines.stream().map(TransferOrderLine::getProductId)
                .toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, TransferReconciliation.Part> figures = reconciliation.byProduct(id, orderLines);
        Map<Long, String> storeNames = stores.findAllById(List.of(order.getSourceStoreId(), order.getDestStoreId()))
                .stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, String> names = users.findAllById(Stream.of(order.getCreatedBy(), order.getDispatchedBy(),
                        order.getExtraDispatchConfirmedBy(), order.getClosedBy()).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        List<TransferOrderDetailResponse.Discrepancy> discrepancies = staff ? List.of() : discrepancies(id);
        return new TransferOrderDetailResponse(id, order.getStatus(), order.getReplenishmentRequestId(),
                order.getSourceStoreId(), storeNames.get(order.getSourceStoreId()), order.getDestStoreId(),
                storeNames.get(order.getDestStoreId()),
                person(order.getCreatedBy(), names), order.getCreatedAt(),
                person(order.getDispatchedBy(), names), order.getDispatchedAt(),
                person(order.getExtraDispatchConfirmedBy(), names), order.getExtraDispatchConfirmedAt(),
                person(order.getClosedBy(), names), order.getClosedAt(),
                orderLines.stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    TransferReconciliation.Part figure = figures.get(line.getProductId());
                    return new TransferOrderDetailResponse.Line(part.getId(), part.getSku(), part.getPartName(),
                            part.getBaseUnit(), line.getPlannedQty(), line.getPreparedQty(), line.getDispatchedQty(),
                            staff ? null : figure.received(), staff ? null : figure.returned(),
                            staff ? null : figure.loss(), staff ? null : figure.extraReceived(),
                            staff ? null : line.getExtraDispatchedQty(), staff ? null : figure.unresolved(),
                            line.getMakeupForCaseId());
                }).toList(),
                orders.findDispatchedLots(id).stream().map(lot -> new TransferOrderDetailResponse.Lot(
                        lot.getProductId(), lot.getLotId(), lot.getManufacturerLotNo(), lot.getExpiryDate(),
                        lot.getQuantity(), owner ? lot.getUnitCost() : null)).toList(),
                orders.findReceipts(id).stream().map(receipt -> new TransferOrderDetailResponse.Receipt(
                        receipt.getId(), receipt.getReceiptType(), receipt.getStatus(), receipt.getArrivedAt(),
                        receipt.getConfirmedAt())).toList(),
                discrepancies);
    }

    private List<TransferOrderDetailResponse.Discrepancy> discrepancies(long transferOrderId) {
        List<DiscrepancyCase> found = orders.findCases(transferOrderId);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DiscrepancyResolutionEvent>> events = orders.findEvents(found.stream()
                .map(DiscrepancyCase::getId).toList()).stream()
                .collect(Collectors.groupingBy(DiscrepancyResolutionEvent::getDiscrepancyCaseId));
        return found.stream().map(shortage -> new TransferOrderDetailResponse.Discrepancy(shortage.getId(),
                shortage.getProductId(), shortage.getShortQty(), shortage.getStatus(), shortage.getOpenedAt(),
                shortage.getClosedAt(), events.getOrDefault(shortage.getId(), List.of()).stream()
                        .map(event -> new TransferOrderDetailResponse.Event(event.getId(), event.getResolutionType(),
                                event.getQuantity(), event.getGoodsReceiptNoteId(), event.getAdjustmentRequestId(),
                                event.getReason(), event.getOccurredAt(), event.getConfirmedAt())).toList()))
                .toList();
    }

    private static Person person(Long id, Map<Long, String> names) {
        return id == null ? null : new Person(id, names.get(id));
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
