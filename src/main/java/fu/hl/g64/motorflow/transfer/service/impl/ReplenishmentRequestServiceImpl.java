package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.LowStockSuggestionResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentRowResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentSearch;
import fu.hl.g64.motorflow.transfer.dto.ToPurchaseRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.helper.RequestedParts;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReplenishmentRequestServiceImpl implements ReplenishmentRequestService {

    private final ReplenishmentRequestRepository requests;
    private final ReplenishmentRequestLineRepository lines;
    private final TransferOrderRepository transferOrders;
    private final TransferOrderLineRepository transferLines;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final StoreScope storeScope;
    private final UserAccountRepository users;
    private final NotificationService notifications;
    private final RequestedParts requestedParts;
    private final TransferReconciliation reconciliation;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    // UC-68
    @Transactional
    @Override
    public ReplenishmentDetailResponse create(AuthenticatedUser user, ReplenishmentCreateRequest body) {
        Store store = requestingStore(user, body.storeId());
        requestedParts.requireActive(body.lines().stream().map(ReplenishmentCreateRequest.Line::productId).toList());
        LocalDateTime now = LocalDateTime.now(clock);
        ReplenishmentRequest request = requests.save(ReplenishmentRequest.create(store.getId(),
                StringUtils.hasText(body.note()) ? body.note().strip() : null, user.id(), now));
        lines.saveAll(body.lines().stream().map(line ->
                ReplenishmentRequestLine.create(request.getId(), line.productId(), line.requestedQty())).toList());
        notifyCoordinators(user, store, request, body.lines().size());
        return detail(request);
    }

    @Transactional(readOnly = true)
    @Override
    public List<LowStockSuggestionResponse> lowStockSuggestions(AuthenticatedUser user, Long storeId) {
        return lines.findLowStockParts(requestingStore(user, storeId).getId()).stream()
                .map(row -> LowStockSuggestionResponse.from(row.getBalance(), row.getProduct())).toList();
    }

    // UC-69
    @Transactional(readOnly = true)
    @Override
    public PageResponse<ReplenishmentRowResponse> list(AuthenticatedUser user, ReplenishmentSearch search, int page,
                                                       int size) {
        Long storeId = storeScope.seesEveryStore(user) ? search.storeId()
                : storeScope.viewableStore(user, search.storeId()).getId();
        Page<ReplenishmentRequestRepository.RequestRow> found = requests.search(search.status(), storeId, search.from(),
                search.to(), PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
        return PageResponse.of(found, found.stream().map(row -> {
            ReplenishmentRequest request = row.getRequest();
            return new ReplenishmentRowResponse(request.getId(), request.getRequestingStoreId(), row.getStoreName(),
                    request.getStatus(), row.getLineCount(), row.getTotalRequestedQty(), request.getCreatedBy(),
                    row.getCreatedByName(), request.getCreatedAt(), request.getClosedAt());
        }).toList());
    }

    // UC-70
    @Transactional(readOnly = true)
    @Override
    public ReplenishmentDetailResponse detail(AuthenticatedUser user, long id) {
        ReplenishmentRequest request = requests.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG95"));
        if (!storeScope.seesEveryStore(user) && !request.getRequestingStoreId().equals(user.storeId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return detail(request);
    }

    // BR-55
    @Transactional
    @Override
    public ReplenishmentDetailResponse toPurchase(AuthenticatedUser user, long id, ToPurchaseRequest body) {
        if (!storeScope.seesEveryStore(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        ReplenishmentRequest request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG95"));
        if (request.getStatus() == ReplenishmentRequestStatus.CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        List<Long> ids = body.lines().stream().map(ToPurchaseRequest.Line::productId).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG93");
        }
        List<ReplenishmentRequestLine> requested = lines.findByReplenishmentRequestIdOrderById(id);
        Map<Long, ReplenishmentRequestLine> byProduct = requested.stream()
                .collect(Collectors.toMap(ReplenishmentRequestLine::getProductId, Function.identity()));
        Map<Long, Long> planned = byProduct(transferLines.plannedByProduct(id));
        Map<Long, Product> parts = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < body.lines().size(); i++) {
            ToPurchaseRequest.Line line = body.lines().get(i);
            ReplenishmentRequestLine target = byProduct.get(line.productId());
            if (target == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG204", i + 1);
            }
            long limit = target.getRequestedQty() - planned.getOrDefault(line.productId(), 0L);
            if (line.toPurchaseQty() > limit) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG205", parts.get(line.productId()).getSku(),
                        Math.max(limit, 0));
            }
            changes.add(passToPurchase(target, line.toPurchaseQty()));
        }
        recordToPurchase(user, request, changes);
        long partCount = requested.stream().filter(line -> line.getToPurchaseQty() > 0).count();
        if (partCount > 0) {
            String message = "Yêu cầu bổ sung #%d có %d phụ tùng cần mua từ nhà cung cấp".formatted(id, partCount);
            users.findByRoleAndStatus(Role.OWNER, AccountStatus.ACTIVE).stream()
                    .filter(owner -> owner.getId() != user.id())
                    .forEach(owner -> notifications.notify(owner.getId(), "REPLENISHMENT_TO_PURCHASE", message,
                            "REPLENISHMENT_REQUEST", id));
        }
        return detail(request);
    }

    // UC-40 with BR-55
    @Transactional
    @Override
    public void prepareForPurchase(AuthenticatedUser owner, long id, long storeId, Map<Long, Integer> quantities,
                                   boolean includeUnallocated) {
        ReplenishmentRequest request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG95"));
        if (!request.getRequestingStoreId().equals(storeId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG260");
        }
        if (request.getStatus() == ReplenishmentRequestStatus.CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        if (requests.findPurchaseOrders(id).stream()
                .anyMatch(row -> row.getPurchaseOrder().getStatus() != PurchaseOrderStatus.CANCELLED)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG261", id);
        }
        List<ReplenishmentRequestLine> requested = lines.findByReplenishmentRequestIdOrderById(id);
        Set<Long> parts = requested.stream().map(ReplenishmentRequestLine::getProductId).collect(Collectors.toSet());
        int lineNo = 0;
        for (Long productId : quantities.keySet()) {
            lineNo++;
            if (!parts.contains(productId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG204", lineNo);
            }
        }
        if (!includeUnallocated) {
            return;
        }
        Map<Long, Long> planned = byProduct(transferLines.plannedByProduct(id));
        List<Map<String, Object>> changes = new ArrayList<>();
        for (ReplenishmentRequestLine line : requested) {
            long unallocated = line.getRequestedQty() - planned.getOrDefault(line.getProductId(), 0L)
                    - line.getToPurchaseQty();
            if (unallocated > 0) {
                changes.add(passToPurchase(line, Math.toIntExact(line.getToPurchaseQty() + unallocated)));
            }
            // A transfer order planned while the Owner had the PO open: the PO would buy more or less than is short.
            if (quantities.getOrDefault(line.getProductId(), 0) != line.getToPurchaseQty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG262", id,
                        products.findById(line.getProductId()).map(Product::getSku).orElseThrow(),
                        line.getToPurchaseQty());
            }
        }
        if (!changes.isEmpty()) {
            recordToPurchase(owner, request, changes);
        }
    }

    /** BR-55: one part's quantity to purchase, and the change for the audit log. */
    private static Map<String, Object> passToPurchase(ReplenishmentRequestLine line, int quantity) {
        Map<String, Object> change = Map.of("product_id", line.getProductId(), "from", line.getToPurchaseQty(), "to",
                quantity);
        line.passToPurchase(quantity);
        return change;
    }

    private void recordToPurchase(AuthenticatedUser user, ReplenishmentRequest request,
                                  List<Map<String, Object>> changes) {
        request.startCoordination();
        auditLog.save(AuditLog.record("REPLENISHMENT_REQUEST", request.getId(), "REPLENISHMENT_TO_PURCHASE_SET",
                user.id(), null, null, Map.of("lines", changes), LocalDateTime.now(clock)));
    }

    /** UC-68: a Branch Manager's own store, or the store the Owner chose; only the Owner may stock a planned store. */
    private Store requestingStore(AuthenticatedUser user, Long requested) {
        boolean owner = user.role() == Role.OWNER;
        if (owner && requested == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        if (!owner && requested != null && !requested.equals(user.storeId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return stores.findById(owner ? requested : user.storeId())
                .filter(s -> s.getStatus() == StoreStatus.ACTIVE || (owner && s.getStatus() == StoreStatus.PLANNED))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
    }

    /** BR-56: the central warehouse Branch Manager and the Owner coordinate; the creator already knows. */
    private void notifyCoordinators(AuthenticatedUser user, Store store, ReplenishmentRequest request, int partCount) {
        List<UserAccount> recipients = new ArrayList<>(users.findByRoleAndStatus(Role.OWNER, AccountStatus.ACTIVE));
        stores.findByIsCentralTrue().ifPresent(central -> recipients.addAll(users.findByRoleAndStoreIdAndStatus(
                Role.BRANCH_MANAGER, central.getId(), AccountStatus.ACTIVE)));
        String message = "Cửa hàng %s yêu cầu bổ sung %d phụ tùng (yêu cầu #%d)."
                .formatted(store.getStoreName(), partCount, request.getId());
        recipients.stream().filter(recipient -> recipient.getId() != user.id()).forEach(recipient ->
                notifications.notify(recipient.getId(), "REPLENISHMENT_REQUESTED", message, "REPLENISHMENT_REQUEST",
                        request.getId()));
    }

    private ReplenishmentDetailResponse detail(ReplenishmentRequest request) {
        long id = request.getId();
        Store store = stores.findById(request.getRequestingStoreId()).orElseThrow();
        String createdByName = users.findById(request.getCreatedBy()).orElseThrow().getFullName();
        List<ReplenishmentRequestLine> requestLines = lines.findByReplenishmentRequestIdOrderById(id);
        Map<Long, Product> parts = products.findAllById(requestLines.stream()
                        .map(ReplenishmentRequestLine::getProductId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Long> planned = byProduct(transferLines.plannedByProduct(id));
        Map<Long, Long> fulfilled = reconciliation.fulfilled(id);
        return new ReplenishmentDetailResponse(id, store.getId(), store.getStoreName(), request.getStatus(),
                request.getNote(), request.getCreatedBy(), createdByName, request.getCreatedAt(), request.getClosedAt(),
                requestLines.stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    long received = fulfilled.getOrDefault(part.getId(), 0L);
                    return new ReplenishmentDetailResponse.Line(part.getId(), part.getSku(), part.getPartName(),
                            part.getBaseUnit(), line.getRequestedQty(), planned.getOrDefault(part.getId(), 0L), received,
                            line.getToPurchaseQty(),
                            Math.max(line.getRequestedQty() - received - line.getToPurchaseQty(), 0));
                }).toList(),
                transferOrders.findLinkedOrders(id).stream().map(row -> {
                    TransferOrder order = row.getTransferOrder();
                    return new ReplenishmentDetailResponse.TransferOrderRef(order.getId(), order.getSourceStoreId(),
                            row.getSourceStoreName(), order.getDestStoreId(), order.getStatus(), order.getCreatedAt());
                }).toList(),
                requests.findPurchaseOrders(id).stream().map(row -> {
                    PurchaseOrder order = row.getPurchaseOrder();
                    return new ReplenishmentDetailResponse.PurchaseOrderRef(order.getId(), order.getSupplierId(),
                            row.getSupplierName(), order.getStatus(), order.getCreatedAt());
                }).toList());
    }

    private static Map<Long, Long> byProduct(List<TransferOrderLineRepository.ProductQty> quantities) {
        return quantities.stream().collect(Collectors.toMap(TransferOrderLineRepository.ProductQty::getProductId,
                TransferOrderLineRepository.ProductQty::getQty));
    }
}
