package fu.hl.g64.motorflow.stockcount.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequestProduct;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.helper.InventoryPeriods;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestProductRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountApproveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountCancelRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountConfirmRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountFilter;
import fu.hl.g64.motorflow.stockcount.dto.StockCountLinesSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountListResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountRecountRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountSubmitRequest;
import fu.hl.g64.motorflow.stockcount.helper.StockCountAccess;
import fu.hl.g64.motorflow.stockcount.helper.StockCountMapper;
import fu.hl.g64.motorflow.stockcount.service.CountSheetService;
import fu.hl.g64.motorflow.stockcount.service.StockCountService;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * UC-90 to UC-95. Each method region names the worker that owns it; a worker adds private helpers inside its own
 * region and new fields or imports at the end of their block.
 */
@Service
@RequiredArgsConstructor
public class StockCountServiceImpl implements StockCountService {

    private final StockCountRepository counts;
    private final StockCountLineRepository lines;
    private final StockCountRequestRepository requests;
    private final StockCountRequestProductRepository requestProducts;
    private final LotBalanceRepository lotBalances;
    private final StockBalanceRepository stockBalances;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final WarehouseTaskRepository tasks;
    private final InventoryPeriods inventoryPeriods;
    private final InventoryPostingService posting;
    private final WarehouseTaskService warehouseTasks;
    private final NotificationService notifications;
    private final CountSheetService countSheets;
    private final StockCountAccess access;
    private final StockCountMapper mapper;
    private final Clock clock;

    // region getPrefill (owner: Y)
    @Override
    @Transactional(readOnly = true)
    public CountRequestResponse getPrefill(AuthenticatedUser user, long requestId) {
        StockCountRequest request = access.loadRequest(requestId);
        access.requireManagerOf(user, request.getStoreId());
        if (!request.isPending()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG233");
        }
        return mapper.toRequest(request);
    }
    // endregion

    // region create (owner: Y)
    @Override
    @Transactional
    public StockCountDetailResponse create(AuthenticatedUser user, StockCountCreateRequest request) {
        if (request.countType() == CountType.OPENING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG244");
        }
        if (user.storeId() == null) { // the Owner has no warehouse
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        access.requireManagerOf(user, user.storeId());
        long storeId = user.storeId();
        if (counts.existsByStoreIdAndStatusIn(storeId,
                List.of(StockCountStatus.DRAFT, StockCountStatus.COUNTING, StockCountStatus.AWAITING_APPROVAL))) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG230");
        }
        LocalDate today = LocalDate.now(clock);
        if (request.countDate().isAfter(today.withDayOfMonth(today.lengthOfMonth()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG232");
        }
        if (request.countDate().withDayOfMonth(1).equals(today.withDayOfMonth(1))) {
            inventoryPeriods.openIfMissing(request.countDate());
        }
        Long periodId = inventoryPeriods.findFor(request.countDate()).filter(InventoryPeriod::isOpen)
                .map(InventoryPeriod::getId).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "MSG232"));

        List<Long> productIds = request.productIds() == null ? List.of() : request.productIds();
        String scopeNote = request.scopeNote() == null || request.scopeNote().isBlank() ? null
                : request.scopeNote().strip();
        StockCountRequest answered = null;
        if (request.stockCountRequestId() != null) {
            answered = access.lockRequest(request.stockCountRequestId());
            access.requireManagerOf(user, answered.getStoreId());
            if (!answered.isPending()) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG233");
            }
            Set<Long> requested = requestProducts.findByStockCountRequestIdOrderByProductId(answered.getId()).stream()
                    .map(StockCountRequestProduct::getProductId).collect(Collectors.toSet());
            if (request.countType() != CountType.CYCLE || request.scopeType() != answered.getScopeType()
                    || !Objects.equals(scopeNote, answered.getScopeNote()) || !requested.equals(new HashSet<>(productIds))) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG234");
            }
        }
        validateScope(storeId, request.scopeType(), scopeNote, productIds);

        LocalDateTime now = LocalDateTime.now(clock);
        StockCount count = counts.save(StockCount.create(storeId, periodId, answered == null ? null : answered.getId(),
                request.countType(), request.countDate(), request.scopeType(), scopeNote, user.id(), now));
        countSheets.buildLines(count, productIds);
        if (answered != null) {
            answered.markDone();
        }
        return mapper.toDetail(count, StockCountAccess.Viewer.MANAGER);
    }

    /** MSG02 for a scope that does not fit its type, MSG242 for a part that does not exist. */
    private void validateScope(long storeId, ScopeType scopeType, String scopeNote, List<Long> productIds) {
        boolean bad = switch (scopeType) {
            case WHOLE -> !productIds.isEmpty();
            case AREA -> !productIds.isEmpty() || scopeNote == null
                    || !stockBalances.findStorageAreas(storeId).contains(scopeNote);
            case PRODUCTS -> productIds.isEmpty() || new HashSet<>(productIds).size() != productIds.size();
        };
        if (bad) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        if (products.findAllById(productIds).size() != productIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG242");
        }
    }
    // endregion

    // region getStockCounts (owner: X)
    @Override
    @Transactional(readOnly = true)
    public StockCountListResponse getStockCounts(AuthenticatedUser user, StockCountFilter filter, Pageable pageable) {
        Long storeId = filter.storeId();
        Long counterId = null;
        if (user.role() == Role.BRANCH_MANAGER) {
            Long own = user.storeId();
            access.requireManagerOf(user, storeId != null ? storeId : own != null ? own : -1L);
            storeId = own;
        } else if (user.role() == Role.STAFF) {
            counterId = user.id();
        }
        Page<Object[]> found = counts.search(storeId, filter.countType(), filter.status(), filter.from(),
                filter.to(), counterId, pageable);
        List<StockCountListResponse.Row> rows = found.stream().map(row -> {
            StockCount c = (StockCount) row[0];
            return new StockCountListResponse.Row(c.getId(), c.getStoreId(), (String) row[1], c.getCountType(),
                    c.getCountDate(), c.getScopeType(), c.getScopeNote(), c.getStatus(),
                    c.getStockCountRequestId(), c.getCreatedAt(), c.getConfirmedAt());
        }).toList();
        List<CountRequestResponse> pending = user.role() == Role.BRANCH_MANAGER
                ? mapper.toRequests(requests.search(storeId, StockCountRequestStatus.PENDING)) : List.of();
        return new StockCountListResponse(PageResponse.of(found, rows), pending);
    }
    // endregion

    // region getStockCount (owner: X)
    @Override
    @Transactional(readOnly = true)
    public StockCountDetailResponse getStockCount(AuthenticatedUser user, long id) {
        StockCount count = access.loadCount(id);
        return mapper.toDetail(count, access.viewerOf(user, count));
    }
    // endregion

    // region cancel (owner: X)
    @Override
    @Transactional
    public StockCountDetailResponse cancel(AuthenticatedUser user, long id, StockCountCancelRequest request) {
        StockCount count = access.lockCount(id);
        access.requireManagerOf(user, count.getStoreId());
        if (count.getStatus() != StockCountStatus.DRAFT && count.getStatus() != StockCountStatus.COUNTING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG235");
        }
        if (count.wasConfirmed()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG243");
        }
        count.cancel(user.id(), request.reason().strip(), LocalDateTime.now(clock));
        warehouseTasks.cancelForDocument(TaskType.COUNTING, id);
        if (count.getStockCountRequestId() != null
                || lines.findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(id).stream()
                        .anyMatch(line -> line.getCountedQty() != null)) {
            notifications.notifyOwners("STOCK_COUNT_CANCELLED",
                    "Quản lý đã hủy phiếu kiểm kê #%d của cửa hàng %s.".formatted(id,
                            stores.findById(count.getStoreId()).orElseThrow().getStoreName()),
                    "STOCK_COUNT", id);
        }
        return mapper.toDetail(count, StockCountAccess.Viewer.MANAGER);
    }
    // endregion

    // region start (owner: Y)
    @Override
    @Transactional
    public StockCountDetailResponse start(AuthenticatedUser user, long id) {
        StockCount count = access.lockCount(id);
        access.requireCounter(user, count);
        if (count.getStatus() != StockCountStatus.DRAFT && count.getStatus() != StockCountStatus.COUNTING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG235");
        }
        if (count.needsBookQuantities()) {
            countSheets.captureBookQuantities(count,
                    lines.findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(count.getId()));
            count.startCounting(LocalDateTime.now(clock));
        }
        return mapper.toDetail(count, access.viewerOf(user, count));
    }
    // endregion

    // region saveLines (owner: Y)
    @Override
    @Transactional
    public StockCountDetailResponse saveLines(AuthenticatedUser user, long id, StockCountLinesSaveRequest request) {
        StockCount count = access.lockCount(id);
        requireRecording(user, count);
        recordLines(user, count, request.lines());
        return mapper.toDetail(count, access.viewerOf(user, count));
    }

    /** SC-9, then SC-6 unless the count is being counted and the result was not sent yet. */
    private void requireRecording(AuthenticatedUser user, StockCount count) {
        WarehouseTask task = access.requireCounter(user, count);
        if (count.getStatus() != StockCountStatus.COUNTING || count.needsBookQuantities()
                || task.getStatus() != WarehouseTaskStatus.ASSIGNED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG235");
        }
    }

    /** SC-8 for a line of another count, a quantity below 0, or a line other than the recounted ones. */
    private void recordLines(AuthenticatedUser user, StockCount count, List<StockCountLinesSaveRequest.Line> sent) {
        if (sent == null || sent.isEmpty()) {
            return;
        }
        Map<Long, StockCountLine> own = lines.findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(count.getId())
                .stream().collect(Collectors.toMap(StockCountLine::getId, Function.identity()));
        boolean recounting = own.values().stream().anyMatch(StockCountLine::isRecountRequested);
        LocalDateTime now = LocalDateTime.now(clock);
        for (StockCountLinesSaveRequest.Line item : sent) {
            StockCountLine line = own.get(item.lineId());
            if (line == null || (item.countedQty() != null && item.countedQty() < 0)
                    || (recounting && !line.isRecountRequested())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG237");
            }
        }
        for (StockCountLinesSaveRequest.Line item : sent) {
            own.get(item.lineId()).record(item.countedQty(), user.id(), now);
        }
    }
    // endregion

    // region submit (owner: Y)
    @Override
    @Transactional
    public StockCountDetailResponse submit(AuthenticatedUser user, long id, StockCountSubmitRequest request) {
        StockCount count = access.lockCount(id);
        requireRecording(user, count);
        recordLines(user, count, request == null ? null : request.lines());
        warehouseTasks.submitResult(user, TaskType.COUNTING, count.getId());
        notifications.notifyStoreManagers(count.getStoreId(), "STOCK_COUNT_SUBMITTED",
                String.format("Staff đã gửi kết quả kiểm kê phiếu kiểm kê #%d.", count.getId()), "STOCK_COUNT",
                count.getId());
        return mapper.toDetail(count, access.viewerOf(user, count));
    }
    // endregion

    // region confirm (owner: Z)
    @Override
    public StockCountDetailResponse confirm(AuthenticatedUser user, long id, StockCountConfirmRequest request) {
        throw new UnsupportedOperationException("UC-94");
    }
    // endregion

    // region requestRecount (owner: Z)
    @Override
    public StockCountDetailResponse requestRecount(AuthenticatedUser user, long id, StockCountRecountRequest request) {
        throw new UnsupportedOperationException("UC-94");
    }
    // endregion

    // region approve (owner: Z)
    @Override
    public StockCountDetailResponse approve(AuthenticatedUser user, long id, StockCountApproveRequest request) {
        throw new UnsupportedOperationException("UC-95");
    }
    // endregion
}
