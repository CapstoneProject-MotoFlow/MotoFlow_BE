package fu.hl.g64.motorflow.stockcount.service.impl;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.OpeningCostsResponse;
import fu.hl.g64.motorflow.stockcount.dto.OpeningCostsSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.OpeningLinesSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.helper.OpeningBalances;
import fu.hl.g64.motorflow.stockcount.helper.StockCountAccess;
import fu.hl.g64.motorflow.stockcount.helper.StockCountMapper;
import fu.hl.g64.motorflow.stockcount.service.OpeningBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpeningBalanceServiceImpl implements OpeningBalanceService {

    private final StockCountLineRepository lines;
    private final ProductRepository products;
    private final StockCountAccess access;
    private final StockCountMapper mapper;
    private final OpeningBalances openingBalances;
    private final Clock clock;

    @Override
    @Transactional
    public StockCountDetailResponse saveLines(AuthenticatedUser user, long countId, OpeningLinesSaveRequest request) {
        StockCount count = access.lockCount(countId);
        WarehouseTask task = access.requireCounter(user, count);
        List<StockCountLine> old = lines.findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(count.getId());
        // While lines are sent back for recount, only those are counted again, through the usual line save.
        if (count.getCountType() != CountType.OPENING || count.getStatus() != StockCountStatus.COUNTING
                || count.needsBookQuantities() || task.getStatus() != WarehouseTaskStatus.ASSIGNED
                || old.stream().anyMatch(StockCountLine::isRecountRequested)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG235");
        }
        List<OpeningLinesSaveRequest.Line> sent = request.lines();
        Set<Long> partIds = sent.stream().map(OpeningLinesSaveRequest.Line::productId).collect(Collectors.toSet());
        Map<Long, Product> parts = products.findAllById(partIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (parts.size() != partIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG242");
        }
        Set<SimpleEntry<Long, LocalDate>> seen = new HashSet<>();
        for (OpeningLinesSaveRequest.Line item : sent) {
            if (!seen.add(new SimpleEntry<>(item.productId(), item.expiryDate()))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG298", parts.get(item.productId()).getSku(),
                        item.expiryDate() == null ? "không hạn"
                                : item.expiryDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            }
            if (damaged(item) > item.countedQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG266");
            }
        }

        lines.deleteAll(old);
        lines.flush();
        LocalDateTime now = LocalDateTime.now(clock);
        lines.saveAll(sent.stream().map(item -> {
            StockCountLine line = StockCountLine.create(count.getId(), item.productId(), null, item.expiryDate());
            line.takeBookQty(0, 0);
            line.record(item.countedQty(), damaged(item), user.id(), now);
            return line;
        }).toList());
        return mapper.toDetail(count, access.viewerOf(user, count));
    }

    @Override
    @Transactional(readOnly = true)
    public OpeningCostsResponse costs(AuthenticatedUser user, long countId) {
        access.requireOwner(user);
        StockCount count = access.loadCount(countId);
        if (count.getCountType() != CountType.OPENING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG235");
        }
        return openingBalances.preview(count);
    }

    @Override
    @Transactional
    public OpeningCostsResponse saveCosts(AuthenticatedUser user, long countId, OpeningCostsSaveRequest request) {
        access.requireOwner(user);
        StockCount count = access.lockCount(countId);
        if (count.getCountType() != CountType.OPENING || count.getStatus() != StockCountStatus.AWAITING_APPROVAL) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG235");
        }
        Set<Long> inCount = openingBalances.countedLines(count).stream().map(StockCountLine::getProductId)
                .collect(Collectors.toSet());
        Map<Long, Long> costs = request.costs().stream().collect(Collectors.toMap(
                OpeningCostsSaveRequest.Cost::productId, OpeningCostsSaveRequest.Cost::initialUnitCost, (a, b) -> b));
        if (!inCount.containsAll(costs.keySet())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG237");
        }
        Map<Long, Product> parts = products.findAllById(costs.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Map.Entry<Long, Long> e : costs.entrySet()) {
            Product part = parts.get(e.getKey());
            if (openingBalances.costLocked(part) && !part.getInitialUnitCost().equals(e.getValue())) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG299", part.getSku());
            }
        }
        costs.forEach((id, cost) -> parts.get(id).enterInitialUnitCost(cost));
        return openingBalances.preview(count);
    }

    private static int damaged(OpeningLinesSaveRequest.Line item) {
        return item.damagedQty() == null ? 0 : item.damagedQty();
    }
}
