package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
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
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
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
    private final Clock clock;

    // UC-68
    @Transactional
    @Override
    public ReplenishmentDetailResponse create(AuthenticatedUser user, ReplenishmentCreateRequest body) {
        Store store = requestingStore(user, body.storeId());
        checkRequestedParts(body.lines());
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

    private void checkRequestedParts(List<ReplenishmentCreateRequest.Line> requested) {
        List<Long> ids = requested.stream().map(ReplenishmentCreateRequest.Line::productId).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG93");
        }
        Map<Long, Product> found = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Long id : ids) {
            Product part = found.get(id);
            if (part == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
            }
            if (part.getStatus() != ProductStatus.ACTIVE) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG94", part.getSku());
            }
        }
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
        Map<Long, Long> fulfilled = byProduct(transferLines.fulfilledByProduct(id));
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
