package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.LowStockSuggestionResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
import lombok.RequiredArgsConstructor;
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
    private final ProductRepository products;
    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final NotificationService notifications;
    private final Clock clock;

    // UC-68
    @Transactional
    @Override
    public ReplenishmentDetailResponse create(AuthenticatedUser user, ReplenishmentCreateRequest body) {
        Store store = requestingStore(user, body.storeId());
        Map<Long, Product> parts = requestedParts(body.lines());
        LocalDateTime now = LocalDateTime.now(clock);
        ReplenishmentRequest request = requests.save(ReplenishmentRequest.create(store.getId(),
                StringUtils.hasText(body.note()) ? body.note().strip() : null, user.id(), now));
        List<ReplenishmentRequestLine> saved = lines.saveAll(body.lines().stream().map(line ->
                ReplenishmentRequestLine.create(request.getId(), line.productId(), line.requestedQty())).toList());
        notifyCoordinators(user, store, request, saved.size());
        return detail(user, request, store, parts, saved);
    }

    @Transactional(readOnly = true)
    @Override
    public List<LowStockSuggestionResponse> lowStockSuggestions(AuthenticatedUser user, Long storeId) {
        return lines.findLowStockParts(requestingStore(user, storeId).getId()).stream()
                .map(row -> LowStockSuggestionResponse.from(row.getBalance(), row.getProduct())).toList();
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

    private Map<Long, Product> requestedParts(List<ReplenishmentCreateRequest.Line> requested) {
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
        return found;
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

    private ReplenishmentDetailResponse detail(AuthenticatedUser user, ReplenishmentRequest request, Store store,
                                               Map<Long, Product> parts, List<ReplenishmentRequestLine> requestLines) {
        return new ReplenishmentDetailResponse(request.getId(), store.getId(), store.getStoreName(), request.getStatus(),
                request.getNote(), request.getCreatedBy(), user.fullName(), request.getCreatedAt(), request.getClosedAt(),
                requestLines.stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    return new ReplenishmentDetailResponse.Line(part.getId(), part.getSku(), part.getPartName(),
                            part.getBaseUnit(), line.getRequestedQty(), 0, 0, line.getToPurchaseQty(),
                            line.getRequestedQty());
                }).toList(), List.of(), List.of());
    }
}
