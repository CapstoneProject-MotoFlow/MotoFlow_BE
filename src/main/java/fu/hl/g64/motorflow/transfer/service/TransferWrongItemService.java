package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.PendingWrongItemResponse;
import fu.hl.g64.motorflow.transfer.dto.RejectWrongItemRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.WrongItemRequest;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.helper.SourceLots;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PM 23/11 (giao sai khi điều chuyển, cases 4–6). The source sent part Y as well as, or instead of, part X: Y is at the
 * destination but still on the source's books. The destination's BM records a phiếu nhận hàng giao sai (Y, quantity,
 * expiry, photos; nothing moves). GVHD 24/11: the source's BM, not the Owner, confirms it, which moves Y from the
 * source's sellable stock into the destination, or rejects it with a reason, which leaves the items undecided again.
 * The part ordered is never touched: what of it did not arrive is a shortage like any other. If the receiving store
 * counted wrong, the Owner uses "receiver miscounted" on the line, which cancels the phiếu still awaiting the source.
 */
@Service
@RequiredArgsConstructor
public class TransferWrongItemService {

    private static final String ENTITY = "TRANSFER_ORDER";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TransferOrderRepository orders;
    private final TransferOrderLineRepository lines;
    private final TransferReconciliation reconciliation;
    private final DiscrepancyCaseRepository cases;
    private final DiscrepancyResolutionEventRepository events;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final StockBalanceRepository balances;
    private final PickingGuide guide;
    private final InventoryPostingService posting;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final AttachmentService attachments;
    private final TransferOrderService transferOrders;
    private final Clock clock;

    /**
     * The destination's BM (or the Owner) records a phiếu nhận hàng giao sai with its photos; GVHD 24/11: the source's
     * BM is told, as they confirm it.
     */
    @Transactional
    public TransferOrderDetailResponse record(AuthenticatedUser user, long orderId, WrongItemRequest body) {
        TransferOrder order = reconciling(orderId);
        requireDestination(user, order);
        Product ordered = part(body.productId());
        DiscrepancyCase found = lockedCase(order, ordered);
        Product received = receivedPart(ordered, body.receivedProductId());
        requireWrongPending(order, ordered, body.quantity());
        LocalDateTime now = LocalDateTime.now(clock);
        String reason = text(body.reason());
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.wrongItemReceipt(found.getId(),
                body.quantity(), received.getId(), body.expiryDate(), reason, user.id(), now));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event_id", event.getId());
        data.put("product_id", ordered.getId());
        data.put("received_product_id", received.getId());
        data.put("quantity", body.quantity());
        data.put("expiry_date", body.expiryDate() == null ? null : body.expiryDate().toString());
        attachments.attach(OwnerType.DISCREPANCY_RESOLUTION_EVENT, event.getId(),
                body.files() == null ? List.of() : body.files(), user.id());
        auditLog.save(AuditLog.record(ENTITY, orderId, "TRANSFER_WRONG_ITEM_RECORDED", user.id(), reason, null, data,
                now));
        notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_WRONG_ITEM",
                ("Phiếu chuyển kho #%d: kho nhận nhận %s x%d giao sai kèm %s, cần kho gửi xác nhận hoặc từ chối")
                        .formatted(orderId, received.getSku(), body.quantity(), ordered.getSku()), ENTITY, orderId);
        return transferOrders.detail(user, orderId);
    }

    /**
     * Phiếu nhận hàng giao sai awaiting confirmation, newest first, with the source lots it would take now and its
     * photos. GVHD 24/11: a BM sees those of orders sent from their store, to confirm or reject; the Owner sees all,
     * to view only.
     */
    @Transactional(readOnly = true)
    public List<PendingWrongItemResponse> pending(AuthenticatedUser user, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        List<DiscrepancyResolutionEvent> rows;
        if (user.role() == Role.OWNER) {
            rows = events.findPendingTransferSwaps(pageable);
        } else if (user.role() == Role.BRANCH_MANAGER && user.storeId() != null) {
            rows = events.findPendingTransferSwapsFromSource(user.storeId(), pageable);
        } else {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, DiscrepancyCase> caseById = cases.findAllById(rows.stream()
                        .map(DiscrepancyResolutionEvent::getDiscrepancyCaseId).distinct().toList()).stream()
                .collect(Collectors.toMap(DiscrepancyCase::getId, Function.identity()));
        Map<Long, TransferOrder> orderById = orders.findAllById(caseById.values().stream()
                        .map(DiscrepancyCase::getTransferOrderId).distinct().toList()).stream()
                .collect(Collectors.toMap(TransferOrder::getId, Function.identity()));
        Map<Long, String> storeNames = stores.findAllById(orderById.values().stream()
                        .flatMap(order -> Stream.of(order.getSourceStoreId(), order.getDestStoreId())).distinct().toList())
                .stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, Product> parts = products.findAllById(rows.stream().flatMap(event -> Stream.of(
                        caseById.get(event.getDiscrepancyCaseId()).getProductId(), event.getReceivedProductId()))
                .distinct().toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, String> names = users.findAllById(rows.stream().map(DiscrepancyResolutionEvent::getRecordedBy)
                .distinct().toList()).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        Map<Long, List<AttachmentResponse>> photos = attachments.list(OwnerType.DISCREPANCY_RESOLUTION_EVENT,
                rows.stream().map(DiscrepancyResolutionEvent::getId).toList());
        LocalDate today = LocalDate.now(clock);
        Map<Long, Map<Long, PickingGuide.Part>> stockBySource = new LinkedHashMap<>();
        rows.stream().collect(Collectors.groupingBy(event -> orderById.get(caseById.get(event.getDiscrepancyCaseId())
                        .getTransferOrderId()).getSourceStoreId()))
                .forEach((source, ofSource) -> stockBySource.put(source, guide.parts(source, ofSource.stream()
                        .map(DiscrepancyResolutionEvent::getReceivedProductId).distinct().toList(), today)));
        return rows.stream().map(event -> {
            DiscrepancyCase found = caseById.get(event.getDiscrepancyCaseId());
            TransferOrder order = orderById.get(found.getTransferOrderId());
            Product ordered = parts.get(found.getProductId());
            Product received = parts.get(event.getReceivedProductId());
            PickingGuide.Part available = stockBySource.get(order.getSourceStoreId()).get(received.getId());
            SourceLots.Plan plan = SourceLots.plan(event.getQuantity(), event.getReceivedExpiryDate(), available);
            return new PendingWrongItemResponse(event.getId(), order.getId(), order.getSourceStoreId(),
                    storeNames.get(order.getSourceStoreId()), order.getDestStoreId(),
                    storeNames.get(order.getDestStoreId()), ordered.getId(), ordered.getSku(), ordered.getPartName(),
                    received.getId(), received.getSku(), received.getPartName(), event.getQuantity(),
                    event.getReceivedExpiryDate(), event.getReason(), names.get(event.getRecordedBy()),
                    event.getOccurredAt(), available == null ? 0 : available.sellableQty(), plan.lots(),
                    plan.blockedCode(), photos.getOrDefault(event.getId(), List.of()));
        }).toList();
    }

    /**
     * GVHD 24/11: the source's BM confirms a phiếu nhận hàng giao sai (the Owner only views it, MSG05). The source must
     * have Y sellable (what open sales orders hold is not
     * taken): of the expiry the BM recorded, or the oldest lots without one. Otherwise MSG339 or MSG341 and nothing
     * moves. The part ordered is not touched. Lock order: order, event, stock of the lower store id, then the higher.
     */
    @Transactional
    public TransferOrderDetailResponse confirm(AuthenticatedUser user, long orderId, long eventId) {
        TransferOrder order = reconciling(orderId);
        requireSource(user, order);
        DiscrepancyResolutionEvent event = awaiting(order, eventId);
        DiscrepancyCase found = cases.findById(event.getDiscrepancyCaseId()).orElseThrow();
        Product ordered = part(found.getProductId());
        Product received = part(event.getReceivedProductId());
        long source = order.getSourceStoreId();
        long destination = order.getDestStoreId();
        balances.findByStoreForUpdate(Math.min(source, destination), List.of(received.getId()));
        balances.findByStoreForUpdate(Math.max(source, destination), List.of(received.getId()));

        int quantity = event.getQuantity();
        PickingGuide.Part available = guide.parts(source, List.of(received.getId()), LocalDate.now(clock))
                .get(received.getId());
        SourceLots.Plan plan = SourceLots.plan(quantity, event.getReceivedExpiryDate(), available);
        if ("MSG341".equals(plan.blockedCode())) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG341", received.getSku(),
                    event.getReceivedExpiryDate().format(DAY));
        }
        if (plan.blockedCode() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG339", received.getSku(),
                    available == null ? 0 : available.sellableQty(), quantity);
        }
        String note = "Nhận hàng giao sai %s kèm %s".formatted(received.getSku(), ordered.getSku());
        posting.moveAll(List.of(new MovePosting(received.getId(), source, StockStatus.AVAILABLE, destination,
                StockStatus.AVAILABLE, quantity,
                plan.lots().stream().map(lot -> new LotQuantity(lot.lotId(), lot.quantity())).toList(),
                MovementType.TRANSFER_WRONG_ITEM, SourceRef.discrepancyResolutionEvent(event.getId()), user.id(),
                note)));
        LocalDateTime now = LocalDateTime.now(clock);
        event.confirmWrongItemReceipt(user.id(), now);
        auditLog.save(AuditLog.record(ENTITY, orderId, "TRANSFER_WRONG_ITEM_CONFIRMED", user.id(), null, null,
                Map.of("event_id", event.getId(), "product_id", ordered.getId(), "received_product_id",
                        received.getId(), "quantity", quantity), now));
        String message = "Phiếu chuyển kho #%d: kho gửi đã xác nhận nhận %s x%d giao sai, kho gửi đã trừ, kho nhận đã cộng"
                .formatted(orderId, received.getSku(), quantity);
        notifications.notifyStoreManagers(source, "TRANSFER_WRONG_ITEM", message, ENTITY, orderId);
        notifications.notifyStoreManagers(destination, "TRANSFER_WRONG_ITEM", message, ENTITY, orderId);
        return transferOrders.detail(user, orderId);
    }

    /**
     * GVHD 24/11: the source's BM rejects a phiếu nhận hàng giao sai, with a reason (MSG361). Nothing moves; its wrong
     * items are undecided again, and the destination's BM is told so they record them again or hand them back.
     */
    @Transactional
    public TransferOrderDetailResponse reject(AuthenticatedUser user, long orderId, long eventId,
                                              RejectWrongItemRequest body) {
        String reason = body == null ? null : text(body.reason());
        if (reason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG361");
        }
        TransferOrder order = reconciling(orderId);
        requireSource(user, order);
        DiscrepancyResolutionEvent event = awaiting(order, eventId);
        Product received = part(event.getReceivedProductId());
        LocalDateTime now = LocalDateTime.now(clock);
        event.rejectWrongItemReceipt(user.id(), reason, now);
        auditLog.save(AuditLog.record(ENTITY, orderId, "TRANSFER_WRONG_ITEM_REJECTED", user.id(), reason, null,
                Map.of("event_id", event.getId(), "received_product_id", received.getId(), "quantity",
                        event.getQuantity()), now));
        notifications.notifyStoreManagers(order.getDestStoreId(), "TRANSFER_WRONG_ITEM",
                "Phiếu chuyển kho #%d: kho gửi từ chối phiếu nhận %s x%d giao sai. Lý do: %s"
                        .formatted(orderId, received.getSku(), event.getQuantity(), reason), ENTITY, orderId);
        return transferOrders.detail(user, orderId);
    }

    private TransferOrder reconciling(long orderId) {
        TransferOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (order.getStatus() != TransferOrderStatus.RECONCILING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        return order;
    }

    /** GVHD 24/11: only the source store's BM confirms or rejects; the Owner views. */
    private static void requireSource(AuthenticatedUser user, TransferOrder order) {
        if (user.role() != Role.BRANCH_MANAGER || !Objects.equals(user.storeId(), order.getSourceStoreId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
    }

    /** Vinh 23/11: the receiving store's BM handles its wrong items; the Owner may too. */
    private static void requireDestination(AuthenticatedUser user, TransferOrder order) {
        boolean manager = user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getDestStoreId());
        if (!manager && user.role() != Role.OWNER) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
    }

    private Product part(long productId) {
        return products.findById(productId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57"));
    }

    private DiscrepancyCase lockedCase(TransferOrder order, Product ordered) {
        DiscrepancyCase found = orders.findCases(order.getId()).stream()
                .filter(item -> item.getProductId().equals(ordered.getId()) && item.isOpen()).findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG216", ordered.getSku()));
        return cases.findByIdForUpdate(found.getId()).orElseThrow();
    }

    /** MSG309 when fewer wrong items of the part have no phiếu nhận hàng giao sai yet. */
    private void requireWrongPending(TransferOrder order, Product ordered, int quantity) {
        TransferReconciliation.Part figure = reconciliation.byProduct(order.getId(),
                lines.findByTransferOrderIdOrderByProductId(order.getId())).get(ordered.getId());
        int pending = figure == null ? 0 : figure.wrongPending();
        if (quantity > pending) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG309", pending);
        }
    }

    /** As for a supplier's wrong item: on sale (MSG313), not the part ordered (MSG310), not a dented variant (MSG317). */
    private Product receivedPart(Product ordered, long productId) {
        Product received = products.findById(productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG57"));
        if (received.getId().equals(ordered.getId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG310", ordered.getSku());
        }
        if (received.getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG313", received.getSku());
        }
        if (received.getVariantOfProductId() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG317", received.getSku());
        }
        return received;
    }

    private DiscrepancyResolutionEvent awaiting(TransferOrder order, long eventId) {
        DiscrepancyResolutionEvent event = events.findByIdForUpdate(eventId)
                .filter(found -> found.getResolutionType() == ResolutionType.RECEIVED_AS_OTHER_PRODUCT)
                .filter(found -> cases.findById(found.getDiscrepancyCaseId())
                        .map(item -> order.getId().equals(item.getTransferOrderId())).orElse(false))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG70"));
        if (event.getStatus() != WrongItemReceiptStatus.AWAITING_SOURCE) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        return event;
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
