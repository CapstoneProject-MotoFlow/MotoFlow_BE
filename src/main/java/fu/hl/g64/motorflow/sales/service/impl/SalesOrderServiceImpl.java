package fu.hl.g64.motorflow.sales.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.sales.dto.ConfirmSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.NextAction;
import fu.hl.g64.motorflow.sales.dto.PickingRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail.Actions;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail.Closing;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail.HistoryEntry;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail.Picker;
import fu.hl.g64.motorflow.sales.dto.SalesOrderLineRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderPreview;
import fu.hl.g64.motorflow.sales.dto.SalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderSearch;
import fu.hl.g64.motorflow.sales.dto.SalesOrderSummary;
import fu.hl.g64.motorflow.sales.dto.SalesOrderUpdateRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderVersion;
import fu.hl.g64.motorflow.sales.dto.SalesOrderView;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.helper.OrderCollections;
import fu.hl.g64.motorflow.sales.helper.OrderPricing;
import fu.hl.g64.motorflow.sales.helper.OrderPricing.Priced;
import fu.hl.g64.motorflow.sales.helper.OrderPricing.Totals;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.sales.helper.SalesOrderHistory;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.sales.service.SalesOrderService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalesOrderServiceImpl implements SalesOrderService {

    /** notification.ref_type of a sales order, so that opening the notification opens it. */
    private static final String REF_TYPE = "SALES_ORDER";
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

    private final SalesOrderRepository orders;
    private final SalesOrderLineRepository orderLines;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final AuditLogRepository auditLog;
    private final WarehouseTaskRepository tasks;
    private final WarehouseTaskService warehouseTasks;
    private final NotificationService notifications;
    private final OrderPricing pricing;
    private final PickingGuide guide;
    private final OrderCollections collections;
    private final ReceivableRepository receivables;
    private final AttachmentService attachments;
    private final Clock clock;

    // UC-52
    @Transactional(readOnly = true)
    @Override
    public PageResponse<SalesOrderSummary> list(AuthenticatedUser user, SalesOrderSearch search, int page, int size) {
        Long storeId = search.storeId();
        Long pickerId = null;
        if (user.role() != Role.OWNER) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            storeId = user.storeId();
            if (user.role() == Role.STAFF) {
                pickerId = user.id();
            }
        }
        SalesOrderView view = search.view() == null ? SalesOrderView.OPEN : search.view();
        LocalDate from = search.createdFrom();
        LocalDate to = search.createdTo();
        if (view != SalesOrderView.OPEN) {
            // Screen 8.1.1: the other tabs show the chosen creation days, today by default.
            LocalDate today = LocalDate.now(clock);
            to = to != null ? to : (from != null && from.isAfter(today) ? from : today);
            from = from != null ? from : to;
        }
        Page<SalesOrder> found = orders.findAll(matching(search.q(), storeId, pickerId, view, from, to),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found, summaries(user, found.getContent()));
    }

    // UC-54
    @Transactional(readOnly = true)
    @Override
    public SalesOrderDetail get(AuthenticatedUser user, long id) {
        SalesOrder order = orders.findById(id).orElseThrow(SalesOrderServiceImpl::notFound);
        if (user.role() == Role.STAFF) {
            boolean picking = tasks.findOpenPickings(List.of(id)).stream()
                    .anyMatch(task -> task.getAssignedTo() == user.id());
            if (!picking) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
        } else {
            requireSeller(user, order);
        }
        return detail(user, order);
    }

    // UC-55 step 8
    @Transactional(readOnly = true)
    @Override
    public List<SalesOrderVersion> versions(AuthenticatedUser seller, long id) {
        SalesOrder order = orders.findById(id).orElseThrow(SalesOrderServiceImpl::notFound);
        requireSeller(seller, order);
        List<AuditLog> saved = new ArrayList<>(auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(
                SalesOrderHistory.ENTITY, id, SalesOrderHistory.VERSION_ACTIONS));
        saved.sort(Comparator.comparing(SalesOrderHistory::version));
        Set<Long> productIds = new HashSet<>();
        saved.forEach(step -> SalesOrderHistory.lines(step.getNewData())
                .forEach(line -> productIds.add(SalesOrderHistory.number(line.get("product_id")).longValue())));
        Map<Long, Product> parts = byId(products.findAllById(productIds), Product::getId);
        Map<Long, String> names = names(saved.stream().map(AuditLog::getActorId).collect(Collectors.toSet()));
        return saved.stream().map(step -> {
            Map<String, Object> data = step.getNewData();
            List<SalesOrderVersion.Line> lines = new ArrayList<>();
            List<Priced> priced = new ArrayList<>();
            for (Map<?, ?> line : SalesOrderHistory.lines(data)) {
                long productId = SalesOrderHistory.number(line.get("product_id")).longValue();
                int requested = SalesOrderHistory.number(line.get("requested_qty")).intValue();
                Integer delivered = line.get("delivered_qty") == null ? null
                        : SalesOrderHistory.number(line.get("delivered_qty")).intValue();
                long reference = SalesOrderHistory.number(line.get("reference_price")).longValue();
                long actual = SalesOrderHistory.number(line.get("actual_unit_price")).longValue();
                Product part = parts.get(productId);
                lines.add(new SalesOrderVersion.Line(productId, part == null ? null : part.getSku(),
                        part == null ? null : part.getPartName(), requested, delivered, reference, actual));
                priced.add(new Priced(delivered != null ? delivered : requested, reference, actual));
            }
            Totals totals = OrderPricing.totals(priced);
            String cause = SalesOrderHistory.CREATED.equals(step.getAction()) ? "CREATED" : (String) data.get("cause");
            return new SalesOrderVersion(SalesOrderHistory.version(step), cause, step.getActorId(),
                    names.get(step.getActorId()), step.getOccurredAt(),
                    data.get("customer_id") == null ? null : SalesOrderHistory.number(data.get("customer_id")).longValue(),
                    (String) data.get("customer_name"), (String) data.get("customer_phone"),
                    PriceType.valueOf((String) data.get("price_type")), new BigDecimal((String) data.get("discount_rate")),
                    totals.referenceTotal(), totals.total(), lines);
        }).toList();
    }

    // UC-53 step 5
    @Transactional(readOnly = true)
    @Override
    public SalesOrderPreview preview(AuthenticatedUser seller, SalesOrderRequest request) {
        Store store = sellingStore(seller, request.storeId());
        BigDecimal rate = discountRate(request.priceType(), request.discountRate());
        Map<Long, Product> parts = checkLines(request.lines(), Set.of(), false);
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, Long> prices = pricing.retailPrices(parts.keySet(), now);
        Map<Long, PickingGuide.Part> stock = guide.parts(store.getId(), parts.keySet(), now.toLocalDate());
        BigDecimal threshold = pricing.discountThreshold(now);

        List<SalesOrderPreview.Line> lines = new ArrayList<>();
        List<Priced> priced = new ArrayList<>();
        for (SalesOrderLineRequest line : request.lines()) {
            Product part = parts.get(line.productId());
            Long reference = prices.get(part.getId());
            Long actual = reference == null ? null : OrderPricing.actualUnitPrice(reference, rate);
            if (reference != null) {
                priced.add(new Priced(line.quantity(), reference, actual));
            }
            int sellable = stock.get(part.getId()).sellableQty();
            lines.add(new SalesOrderPreview.Line(part.getId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                    part.getStatus(), line.quantity(), reference, actual,
                    actual == null ? null : (long) line.quantity() * actual, sellable,
                    Math.max(0, line.quantity() - sellable)));
        }
        Totals totals = OrderPricing.totals(priced);
        boolean within = true;
        try {
            OrderPricing.checkThreshold(rate, totals, threshold);
        } catch (BusinessException e) {
            within = false;
        }
        return new SalesOrderPreview(store.getId(), request.priceType(), rate, threshold,
                priced.size() == lines.size(), within, totals.referenceTotal(), totals.discountAmount(),
                totals.total(), totals.invoiceDiscountRate(), lines);
    }

    // UC-53
    @Transactional
    @Override
    public SalesOrderDetail create(AuthenticatedUser seller, SalesOrderRequest request) {
        Store store = sellingStore(seller, request.storeId());
        SalesOrder.Customer customer = customer(request.customerId(), request.walkInName(), request.walkInPhone());
        BigDecimal rate = discountRate(request.priceType(), request.discountRate());
        Map<Long, Product> parts = checkLines(request.lines(), Set.of(), true);
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, Long> prices = requirePrices(request.lines(), parts, now);
        checkPricing(rate, request.lines().stream().map(line -> priced(line.quantity(), prices.get(line.productId()),
                rate)).toList(), now);

        SalesOrder order = orders.save(SalesOrder.create(store.getId(), customer, request.priceType(), rate,
                seller.id(), now));
        List<SalesOrderLine> lines = new ArrayList<>();
        for (int i = 0; i < request.lines().size(); i++) {
            SalesOrderLineRequest line = request.lines().get(i);
            long reference = prices.get(line.productId());
            lines.add(SalesOrderLine.create(order.getId(), i + 1, line.productId(), line.quantity(), reference,
                    OrderPricing.actualUnitPrice(reference, rate)));
        }
        orderLines.saveAll(lines);
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, order.getId(), SalesOrderHistory.CREATED, seller.id(),
                null, null, SalesOrderHistory.snapshot(1, null, order, lines), now));
        if (seller.role() == Role.OWNER) {
            // The store's Branch Manager gives the picking to someone (UC-86).
            notifications.notifyStoreManagers(store.getId(), "SALES_ORDER_CREATED",
                    "Có đơn bán mới %s cần giao việc lấy hàng.".formatted(label(order)), REF_TYPE, order.getId());
        }
        return detail(seller, order);
    }

    // UC-55
    @Transactional
    @Override
    public SalesOrderDetail update(AuthenticatedUser seller, long id, SalesOrderUpdateRequest request) {
        SalesOrder order = lockUnfinished(id);
        requireSeller(seller, order);
        int version = currentVersion(id);
        requireVersion(request.version(), version);

        List<SalesOrderLine> current = orderLines.findBySalesOrderIdOrderByLineNoAsc(id);
        Map<Long, SalesOrderLine> byProduct = current.stream()
                .collect(Collectors.toMap(SalesOrderLine::getProductId, Function.identity()));
        SalesOrder.Customer customer = customer(request.customerId(), request.walkInName(), request.walkInPhone());
        BigDecimal rate = discountRate(request.priceType(), request.discountRate());
        Map<Long, Product> parts = checkLines(request.lines(), byProduct.keySet(), true);

        Map<Long, Integer> wanted = new LinkedHashMap<>();
        request.lines().forEach(line -> wanted.put(line.productId(), line.quantity()));
        Map<Long, Integer> before = new LinkedHashMap<>();
        current.forEach(line -> before.put(line.getProductId(), line.getRequestedQty()));
        boolean linesChanged = !wanted.equals(before);
        boolean customerChanged = !Objects.equals(order.getCustomerId(), customer.id()) || (customer.id() == null
                && (!Objects.equals(order.getCustomerNameSnapshot(), customer.name())
                || !Objects.equals(order.getCustomerPhoneSnapshot(), customer.phone())));
        boolean pricingChanged = order.getPriceType() != request.priceType()
                || order.getDiscountRate().compareTo(rate) != 0;
        if (!linesChanged && !customerChanged && !pricingChanged) {
            return detail(seller, order);
        }

        // BR-18, BR-36: the new version takes the retail prices in effect now.
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, Long> prices = requirePrices(request.lines(), parts, now);
        checkPricing(rate, request.lines().stream().map(line -> priced(line.quantity(), prices.get(line.productId()),
                rate)).toList(), now);

        Map<String, Object> previous = SalesOrderHistory.snapshot(version, null, order, current);
        boolean pickedBefore = order.getStatus() != SalesOrderStatus.AWAITING_PICKING;
        order.revise(customer, request.priceType(), rate, now);
        List<SalesOrderLine> removed = current.stream().filter(line -> !wanted.containsKey(line.getProductId()))
                .toList();
        int nextLineNo = current.stream().mapToInt(SalesOrderLine::getLineNo).max().orElse(0) + 1;
        List<SalesOrderLine> added = new ArrayList<>();
        for (SalesOrderLineRequest line : request.lines()) {
            long reference = prices.get(line.productId());
            long actual = OrderPricing.actualUnitPrice(reference, rate);
            SalesOrderLine existing = byProduct.get(line.productId());
            if (existing == null) {
                added.add(SalesOrderLine.create(id, nextLineNo++, line.productId(), line.quantity(), reference,
                        actual));
            } else {
                if (existing.getRequestedQty() != line.quantity()) {
                    existing.changeRequestedQty(line.quantity());
                }
                existing.reprice(reference, actual);
                existing.clearDelivery();
            }
        }
        orderLines.deleteAll(removed);
        orderLines.saveAll(added);
        orderLines.flush();

        if (linesChanged) {
            // UC-58 needs a picking result for the current content: the picker records it again.
            order.backToPicking(now);
            Long picker = pickedBefore ? Long.valueOf(warehouseTasks.returnForRecount(TaskType.PICKING, id))
                    : tasks.findOpenPickings(List.of(id)).stream().findFirst().map(WarehouseTask::getAssignedTo)
                    .orElse(null);
            if (picker != null && picker != seller.id()) {
                notifications.notify(picker, "SALES_ORDER_CHANGED",
                        "Đơn bán %s đã đổi phụ tùng hoặc số lượng. Vui lòng lấy hàng theo nội dung mới."
                                .formatted(label(order)), REF_TYPE, id);
            }
        }
        List<SalesOrderLine> after = orderLines.findBySalesOrderIdOrderByLineNoAsc(id);
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.VERSIONED, seller.id(), null,
                previous, SalesOrderHistory.snapshot(version + 1, SalesOrderHistory.CAUSE_UPDATE, order, after), now));
        return detail(seller, order);
    }

    // UC-57
    @Transactional
    @Override
    public SalesOrderDetail recordPicking(AuthenticatedUser picker, long id, PickingRequest request) {
        SalesOrder order = lockUnfinished(id);
        if (order.getStatus() == SalesOrderStatus.CONFIRMED) {
            // "Confirmed results cannot be edited."
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        if (order.getStatus() == SalesOrderStatus.AWAITING_PICKING) {
            warehouseTasks.requireAssignee(picker, TaskType.PICKING, id);
        } else {
            warehouseTasks.reopenOwnResult(picker, TaskType.PICKING, id);
        }
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(id);
        int version = currentVersion(id);
        if (request.version() != version && !samePickingAs(id, request.version(), lines)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG165", version);
        }
        Map<Long, Product> parts = byId(products.findAllById(lines.stream().map(SalesOrderLine::getProductId)
                .toList()), Product::getId);
        Map<Long, SalesOrderLine> byProduct = matchLines(lines, parts,
                request.lines().stream().map(PickingRequest.Line::productId).toList());

        List<Map<String, Object>> recorded = new ArrayList<>();
        for (PickingRequest.Line line : request.lines()) {
            SalesOrderLine target = byProduct.get(line.productId());
            if (line.pickedQty() > target.getRequestedQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG168",
                        parts.get(line.productId()).getSku(), target.getRequestedQty());
            }
        }
        for (PickingRequest.Line line : request.lines()) {
            SalesOrderLine target = byProduct.get(line.productId());
            target.recordPicked(line.pickedQty());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.productId());
            row.put("requested_qty", target.getRequestedQty());
            row.put("picked_qty", line.pickedQty());
            row.put("short_reason", line.pickedQty() < target.getRequestedQty() ? text(line.shortReason()) : null);
            recorded.add(row);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        order.picked(now);
        warehouseTasks.submitResult(picker, TaskType.PICKING, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version);
        data.put("lines", recorded);
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.PICKED, picker.id(), null,
                null, data, now));
        long shortLines = lines.stream().filter(line -> line.getPickedQty() < line.getRequestedQty()).count();
        String shortage = shortLines == 0 ? "" : ", %d dòng lấy thiếu".formatted(shortLines);
        notifications.notifyStoreManagers(order.getStoreId(), "SALES_ORDER_PICKED",
                "Đơn bán %s đã lấy hàng xong%s, đang chờ xác nhận.".formatted(label(order), shortage), REF_TYPE, id);
        return detail(picker, order);
    }

    // UC-58
    @Transactional
    @Override
    public SalesOrderDetail confirm(AuthenticatedUser seller, long id, ConfirmSalesOrderRequest request) {
        SalesOrder order = lockUnfinished(id);
        requireSeller(seller, order);
        if (order.getStatus() == SalesOrderStatus.AWAITING_PICKING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG174");
        }
        int version = currentVersion(id);
        requireVersion(request.version(), version);
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(id);
        Map<Long, Product> parts = byId(products.findAllById(lines.stream().map(SalesOrderLine::getProductId)
                .toList()), Product::getId);
        Map<Long, SalesOrderLine> byProduct = matchLines(lines, parts,
                request.lines().stream().map(ConfirmSalesOrderRequest.Line::productId).toList());
        Map<Long, Integer> delivered = new HashMap<>();
        for (ConfirmSalesOrderRequest.Line line : request.lines()) {
            SalesOrderLine target = byProduct.get(line.productId());
            if (line.deliveredQty() > target.getPickedQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG169", parts.get(line.productId()).getSku(),
                        target.getPickedQty());
            }
            delivered.put(line.productId(), line.deliveredQty());
        }
        if (delivered.values().stream().allMatch(quantity -> quantity == 0)) {
            // BR-14: the reference total of the delivered goods must be greater than zero.
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG170");
        }
        boolean confirmed = order.getStatus() == SalesOrderStatus.CONFIRMED;
        if (confirmed && lines.stream().allMatch(line -> Objects.equals(line.getDeliveredQty(),
                delivered.get(line.getProductId())))) {
            return detail(seller, order);
        }
        // BR-36: delivered quantities other than the version's (requested, or confirmed before) make a new version,
        // which takes the retail prices in effect now (BR-18).
        boolean newVersion = confirmed || lines.stream()
                .anyMatch(line -> line.getRequestedQty() != delivered.get(line.getProductId()));
        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal rate = order.getDiscountRate();
        Map<Long, Long> prices = newVersion ? requireLinePrices(lines, parts, now)
                : lines.stream().collect(Collectors.toMap(SalesOrderLine::getProductId,
                SalesOrderLine::getReferencePrice));
        List<Priced> priced = lines.stream().map(line -> newVersion
                ? priced(delivered.get(line.getProductId()), prices.get(line.getProductId()), rate)
                : new Priced(delivered.get(line.getProductId()), line.getReferencePrice(), line.getActualUnitPrice()))
                .toList();
        checkPricing(rate, priced, now);
        Totals totals = OrderPricing.totals(priced);
        checkStock(order, lines, parts, delivered, now.toLocalDate());
        checkMargin(seller, lines, parts, delivered, totals, now);
        warehouseTasks.confirmResult(seller, TaskType.PICKING, id);

        Map<String, Object> previous = newVersion ? SalesOrderHistory.snapshot(version, null, order, lines) : null;
        for (SalesOrderLine line : lines) {
            if (newVersion) {
                long reference = prices.get(line.getProductId());
                line.reprice(reference, OrderPricing.actualUnitPrice(reference, rate));
            }
            line.deliver(delivered.get(line.getProductId()));
        }
        order.confirm(seller.id(), now);
        orderLines.flush();
        int confirmedVersion = newVersion ? version + 1 : version;
        if (newVersion) {
            auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.VERSIONED, seller.id(),
                    null, previous, SalesOrderHistory.snapshot(confirmedVersion, SalesOrderHistory.CAUSE_DELIVERY,
                            order, lines), now));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", confirmedVersion);
        data.put("reference_total", totals.referenceTotal());
        data.put("total", totals.total());
        data.put("lines", lines.stream().map(line -> Map.of("product_id", line.getProductId(),
                "delivered_qty", line.getDeliveredQty())).toList());
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.CONFIRMED, seller.id(), null,
                null, data, now));
        return detail(seller, order);
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * The store the order is sold at: a Branch Manager's own store (MSG05 for another), the store Owner chose
     * (MSG157 if none). MSG46 unless it is active.
     */
    private Store sellingStore(AuthenticatedUser seller, Long storeId) {
        Long id;
        if (seller.role() == Role.OWNER) {
            if (storeId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG157");
            }
            id = storeId;
        } else {
            if (storeId != null && !storeId.equals(seller.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            id = seller.storeId();
        }
        return stores.findById(id).filter(store -> store.getStatus() == StoreStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
    }

    /** UC-55, UC-58: Owner, or the Branch Manager of the order's store (BR-03). */
    private static void requireSeller(AuthenticatedUser user, SalesOrder order) {
        boolean seller = user.role() == Role.OWNER
                || (user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId()));
        if (!seller) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
    }

    private static boolean isSeller(AuthenticatedUser user, SalesOrder order) {
        return user.role() == Role.OWNER
                || (user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId()));
    }

    /** The order, locked until the end of the transaction, unless it is completed or cancelled (MSG164). */
    private SalesOrder lockUnfinished(long id) {
        SalesOrder order = orders.findByIdForUpdate(id).orElseThrow(SalesOrderServiceImpl::notFound);
        if (!order.isUnfinished()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG164");
        }
        return order;
    }

    private int currentVersion(long orderId) {
        return 1 + auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(SalesOrderHistory.ENTITY,
                orderId, List.of(SalesOrderHistory.VERSIONED)).size();
    }

    private static void requireVersion(int opened, int current) {
        if (opened != current) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG165", current);
        }
    }

    /** True if version {@code opened} asked for the same parts and quantities as the order does now. */
    private boolean samePickingAs(long orderId, int opened, List<SalesOrderLine> current) {
        return auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(SalesOrderHistory.ENTITY,
                        orderId, SalesOrderHistory.VERSION_ACTIONS).stream()
                .filter(step -> SalesOrderHistory.version(step) == opened).findFirst()
                .map(step -> SalesOrderHistory.samePicking(step.getNewData(), current)).orElse(false);
    }

    /** A customer profile (MSG74 if unknown), or a walk-in sale with the name and phone given, if any. */
    private SalesOrder.Customer customer(Long customerId, String walkInName, String walkInPhone) {
        if (customerId != null) {
            Customer customer = customers.findById(customerId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG74"));
            return new SalesOrder.Customer(customer.getId(), customer.getCustomerName(), customer.getPhone(),
                    customer.getAddress());
        }
        return new SalesOrder.Customer(null, text(walkInName), text(walkInPhone), null);
    }

    /** BR-10, BR-12: one rate for a wholesale order; a retail order has none (MSG161). */
    private static BigDecimal discountRate(PriceType priceType, BigDecimal rate) {
        BigDecimal value = rate == null ? BigDecimal.ZERO : rate;
        if (priceType == PriceType.RETAIL && value.signum() != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG161");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * UC-53 step 8: MSG158 with the line number for a part already on an earlier line, MSG57 for an unknown part,
     * MSG159 for a discontinued part not already on the order (BR-07) when {@code refuseDiscontinued}.
     */
    private Map<Long, Product> checkLines(List<SalesOrderLineRequest> lines, Set<Long> alreadyOnOrder,
                                          boolean refuseDiscontinued) {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!seen.add(lines.get(i).productId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG158", i + 1);
            }
        }
        Map<Long, Product> parts = byId(products.findAllById(seen), Product::getId);
        for (SalesOrderLineRequest line : lines) {
            Product part = parts.get(line.productId());
            if (part == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG57");
            }
            if (refuseDiscontinued && part.getStatus() == ProductStatus.DISCONTINUED
                    && !alreadyOnOrder.contains(part.getId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG159", part.getSku());
            }
        }
        return parts;
    }

    /** The retail price in effect of every requested part (MSG160 for the first one without). */
    private Map<Long, Long> requirePrices(List<SalesOrderLineRequest> lines, Map<Long, Product> parts,
                                          LocalDateTime now) {
        Map<Long, Long> prices = pricing.retailPrices(parts.keySet(), now);
        for (SalesOrderLineRequest line : lines) {
            if (!prices.containsKey(line.productId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG160", parts.get(line.productId()).getSku());
            }
        }
        return prices;
    }

    private Map<Long, Long> requireLinePrices(List<SalesOrderLine> lines, Map<Long, Product> parts,
                                              LocalDateTime now) {
        return requirePrices(lines.stream().map(line -> new SalesOrderLineRequest(line.getProductId(),
                line.getRequestedQty())).toList(), parts, now);
    }

    private static Priced priced(int quantity, long referencePrice, BigDecimal rate) {
        return new Priced(quantity, referencePrice, OrderPricing.actualUnitPrice(referencePrice, rate));
    }

    /** BR-12, BR-13, BR-16 against the threshold in effect; a unit price discounted to nothing is refused too. */
    private void checkPricing(BigDecimal rate, List<Priced> lines, LocalDateTime now) {
        BigDecimal threshold = pricing.discountThreshold(now);
        if (lines.stream().anyMatch(line -> line.actualUnitPrice() <= 0)) {
            throw threshold == null ? new BusinessException(HttpStatus.BAD_REQUEST, "MSG163")
                    : new BusinessException(HttpStatus.BAD_REQUEST, "MSG162", OrderPricing.percent(threshold));
        }
        OrderPricing.checkThreshold(rate, OrderPricing.totals(lines), threshold);
    }

    /**
     * UC-57 step 5, UC-58: the request names every line of the order once. MSG166 with the row number for a part that
     * is not on the order or is listed twice, MSG167 with the SKUs of the lines left out.
     */
    private static Map<Long, SalesOrderLine> matchLines(List<SalesOrderLine> lines, Map<Long, Product> parts,
                                                        List<Long> requested) {
        Map<Long, SalesOrderLine> byProduct = lines.stream()
                .collect(Collectors.toMap(SalesOrderLine::getProductId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            if (!byProduct.containsKey(requested.get(i)) || !seen.add(requested.get(i))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG166", i + 1);
            }
        }
        String missing = lines.stream().filter(line -> !seen.contains(line.getProductId()))
                .map(line -> parts.get(line.getProductId()).getSku()).collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG167", missing);
        }
        return byProduct;
    }

    /** UC-58 step 6, BR-31, BR-76: the store holds enough unexpired available goods for every delivered line. */
    private void checkStock(SalesOrder order, List<SalesOrderLine> lines, Map<Long, Product> parts,
                            Map<Long, Integer> delivered, LocalDate today) {
        Map<Long, PickingGuide.Part> stock = guide.parts(order.getStoreId(), parts.keySet(), today);
        String lacking = lines.stream().filter(line -> delivered.get(line.getProductId())
                        > stock.get(line.getProductId()).sellableQty())
                .map(line -> "%s cần %d, còn %d".formatted(parts.get(line.getProductId()).getSku(),
                        delivered.get(line.getProductId()), stock.get(line.getProductId()).sellableQty()))
                .collect(Collectors.joining("; "));
        if (!lacking.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG171", lacking);
        }
    }

    /**
     * BR-88: order total ≥ delivered goods at the moving average cost × (1 + minimum margin). Only Owner learns the
     * amount (MSG173, BR-72); a Branch Manager is told the price is too low (MSG172).
     */
    private void checkMargin(AuthenticatedUser seller, List<SalesOrderLine> lines, Map<Long, Product> parts,
                             Map<Long, Integer> delivered, Totals totals, LocalDateTime now) {
        BigDecimal cost = BigDecimal.ZERO;
        for (SalesOrderLine line : lines) {
            BigDecimal average = parts.get(line.getProductId()).getMovingAvgCost();
            if (average != null) {
                cost = cost.add(average.multiply(BigDecimal.valueOf(delivered.get(line.getProductId()))));
            }
        }
        BigDecimal margin = pricing.minimumMargin(now);
        long minimum = OrderPricing.minimumTotal(cost, margin);
        if (totals.total() < minimum) {
            throw seller.role() == Role.OWNER
                    ? new BusinessException(HttpStatus.BAD_REQUEST, "MSG173", money(minimum),
                    OrderPricing.percent(margin))
                    : new BusinessException(HttpStatus.BAD_REQUEST, "MSG172");
        }
    }

    private List<SalesOrderSummary> summaries(AuthenticatedUser user, List<SalesOrder> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> ids = page.stream().map(SalesOrder::getId).toList();
        Map<Long, Long> versions = auditLog.findByEntityTypeAndEntityIdInAndAction(SalesOrderHistory.ENTITY, ids,
                SalesOrderHistory.VERSIONED).stream().collect(Collectors.groupingBy(AuditLog::getEntityId,
                Collectors.counting()));
        Map<Long, String> storeNames = stores.findAllById(page.stream().map(SalesOrder::getStoreId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, Long> totals = orderLines.currentTotals(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).longValue()));
        Map<Long, WarehouseTask> pickings = tasks.findOpenPickings(ids).stream()
                .collect(Collectors.toMap(WarehouseTask::getSalesOrderId, Function.identity()));
        Map<Long, String> names = names(pickings.values().stream().map(WarehouseTask::getAssignedTo)
                .collect(Collectors.toSet()));
        boolean staff = user.role() == Role.STAFF;

        return page.stream().map(order -> {
            WarehouseTask picking = pickings.get(order.getId());
            return new SalesOrderSummary(order.getId(), 1 + versions.getOrDefault(order.getId(), 0L).intValue(),
                    order.getStatus(), order.getStoreId(), storeNames.get(order.getStoreId()), order.getCustomerId(),
                    order.getCustomerNameSnapshot(), staff ? null : order.getCustomerPhoneSnapshot(),
                    order.getCreatedAt(), staff ? null : totals.getOrDefault(order.getId(), 0L),
                    picking == null ? null : picking.getAssignedTo(),
                    picking == null ? null : names.get(picking.getAssignedTo()), nextAction(user, order, picking));
        }).toList();
    }

    private static NextAction nextAction(AuthenticatedUser user, SalesOrder order, WarehouseTask picking) {
        if (picking != null && picking.getAssignedTo() == user.id()
                && picking.getStatus() == WarehouseTaskStatus.ASSIGNED) {
            return NextAction.RECORD_PICKING;
        }
        if (!isSeller(user, order)) {
            return null;
        }
        return switch (order.getStatus()) {
            case AWAITING_PICKING -> picking == null && user.role() == Role.BRANCH_MANAGER
                    ? NextAction.ASSIGN_PICKER : null;
            case PICKED -> NextAction.CONFIRM;
            case CONFIRMED -> NextAction.COLLECT_PAYMENT;
            case COMPLETED, CANCELLED -> null;
        };
    }

    private SalesOrderDetail detail(AuthenticatedUser user, SalesOrder order) {
        long id = order.getId();
        boolean staff = user.role() == Role.STAFF;
        LocalDateTime now = LocalDateTime.now(clock);
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(id);
        List<AuditLog> steps = auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(
                SalesOrderHistory.ENTITY, id, SalesOrderHistory.ACTIONS);
        int version = 1 + (int) steps.stream().filter(step -> SalesOrderHistory.VERSIONED.equals(step.getAction()))
                .count();
        List<WarehouseTask> assignments = tasks.findHistory(TaskType.PICKING, id);
        Optional<WarehouseTask> holder = assignments.stream().filter(task ->
                task.getStatus() != WarehouseTaskStatus.CANCELLED).findFirst();

        Set<Long> productIds = lines.stream().map(SalesOrderLine::getProductId).collect(Collectors.toSet());
        Map<Long, Product> parts = byId(products.findAllById(productIds), Product::getId);
        boolean unfinished = order.isUnfinished();
        Map<Long, PickingGuide.Part> stock = unfinished
                ? guide.parts(order.getStoreId(), productIds, now.toLocalDate()) : Map.of();
        Map<Long, String> reasons = shortReasons(steps, lines);
        OrderCollections.Money collected = staff ? null : collections.of(id);
        Set<Long> people = new HashSet<>();
        people.add(order.getCreatedBy());
        people.add(order.getConfirmedBy());
        people.add(order.getCreditDecidedBy());
        people.add(order.getCompletedBy());
        people.add(order.getCancelledBy());
        if (collected != null) {
            collected.rows().forEach(row -> {
                people.add(row.getRecordedBy());
                people.add(row.getVerifiedBy());
            });
        }
        steps.forEach(step -> people.add(step.getActorId()));
        assignments.forEach(task -> {
            people.add(task.getAssignedTo());
            people.add(task.getAssignedBy());
        });
        people.remove(null);
        Map<Long, String> names = names(people);

        boolean delivered = lines.stream().allMatch(line -> line.getDeliveredQty() != null);
        List<SalesOrderDetail.Line> rows = new ArrayList<>();
        List<Priced> priced = new ArrayList<>();
        for (SalesOrderLine line : lines) {
            Product part = parts.get(line.getProductId());
            int quantity = delivered ? line.getDeliveredQty() : line.getRequestedQty();
            priced.add(new Priced(quantity, line.getReferencePrice(), line.getActualUnitPrice()));
            PickingGuide.Part held = stock.get(line.getProductId());
            // What still has to come out of the warehouse: the request until picked, then what is to be delivered.
            int needed = line.getDeliveredQty() != null ? line.getDeliveredQty()
                    : order.getStatus() == SalesOrderStatus.AWAITING_PICKING || line.getPickedQty() == null
                    ? line.getRequestedQty() : line.getPickedQty();
            Integer shortQty = line.getPickedQty() == null ? null : line.getRequestedQty() - line.getPickedQty();
            rows.add(new SalesOrderDetail.Line(line.getId(), line.getLineNo(), part.getId(), part.getSku(),
                    part.getPartName(), part.getBaseUnit(), part.getStatus(),
                    held == null ? List.of() : held.barcodes(), held == null ? null : held.storageArea(),
                    held == null ? null : held.binLocation(), line.getRequestedQty(), line.getPickedQty(), shortQty,
                    reasons.get(line.getProductId()), line.getDeliveredQty(),
                    staff ? null : line.getReferencePrice(), staff ? null : line.getActualUnitPrice(),
                    staff ? null : (long) quantity * line.getActualUnitPrice(),
                    held == null ? 0 : held.sellableQty(), held == null ? 0 : Math.max(0, needed - held.sellableQty()),
                    held == null ? List.of() : held.pickFirst(needed)));
        }
        Totals totals = OrderPricing.totals(priced);

        Picker picker = holder.map(task -> new Picker(task.getAssignedTo(), names.get(task.getAssignedTo()),
                task.getStatus())).orElse(null);
        boolean seller = isSeller(user, order);
        boolean holdsTask = holder.filter(task -> task.getAssignedTo() == user.id()).isPresent();
        WarehouseTaskStatus taskStatus = holder.map(WarehouseTask::getStatus).orElse(null);
        SalesOrderDetail.Money money = collected == null ? null : money(order, totals.total(), collected, names);
        boolean confirmedNow = order.getStatus() == SalesOrderStatus.CONFIRMED;
        boolean settled = money != null && money.unverifiedCount() == 0;
        boolean creditAllowed = order.getCustomerId() != null && customers.findById(order.getCustomerId())
                .filter(customer -> customer.getCreditStatus() == CreditStatus.ELIGIBLE).isPresent();
        Actions actions = new Actions(seller && unfinished,
                user.role() == Role.BRANCH_MANAGER && seller && order.getStatus() == SalesOrderStatus.AWAITING_PICKING,
                holdsTask && ((order.getStatus() == SalesOrderStatus.AWAITING_PICKING
                        && taskStatus == WarehouseTaskStatus.ASSIGNED)
                        || (order.getStatus() == SalesOrderStatus.PICKED
                        && taskStatus == WarehouseTaskStatus.RESULT_SUBMITTED)),
                seller && (order.getStatus() == SalesOrderStatus.PICKED || confirmedNow),
                seller && confirmedNow && money.payableNow() > 0,
                seller && unfinished && money.refundable() > 0,
                seller && confirmedNow && settled && creditAllowed && money.remainingPayable() > 0
                        && money.overpaidAmount() == 0,
                seller && confirmedNow && settled && money.remainingPayable() == 0 && money.overpaidAmount() == 0,
                seller && unfinished);
        Closing closing = staff ? null : new Closing(order.getCompletionType(), order.getCompletedBy(),
                names.get(order.getCompletedBy()), order.getCompletedAt(), order.getCancelledBy(),
                names.get(order.getCancelledBy()), order.getCancelledAt(), order.getCancellationReason());

        List<HistoryEntry> history = staff ? List.of() : history(steps, assignments, names);
        String storeName = stores.findById(order.getStoreId()).map(Store::getStoreName).orElse(null);
        return new SalesOrderDetail(id, version, order.getStatus(), order.getStoreId(), storeName,
                order.getCustomerId(), order.getCustomerNameSnapshot(),
                staff ? null : order.getCustomerPhoneSnapshot(), staff ? null : order.getCustomerAddressSnapshot(),
                order.getPriceType(), staff ? null : order.getDiscountRate(), picker,
                order.getCreatedBy(), names.get(order.getCreatedBy()), order.getCreatedAt(), order.getUpdatedAt(),
                order.getConfirmedBy(), names.get(order.getConfirmedBy()), order.getConfirmedAt(),
                delivered ? "DELIVERED" : "REQUESTED", staff ? null : totals.referenceTotal(),
                staff ? null : totals.discountAmount(), staff ? null : totals.total(),
                staff ? null : totals.invoiceDiscountRate(), staff ? null : pricing.discountThreshold(now), money,
                closing, rows, actions, history);
    }

    /** Screen 8.2.2: what the order holds of the customer's money, its payments and refunds, credit and receivable. */
    private SalesOrderDetail.Money money(SalesOrder order, long total, OrderCollections.Money held,
                                         Map<Long, String> names) {
        boolean unfinished = order.isUnfinished();
        long net = held.netCollected();
        Map<Long, List<AttachmentResponse>> evidence = held.rows().isEmpty()
                ? Map.of() : attachments.list(OwnerType.PAYMENT, held.rows().stream().map(Payment::getId).toList());
        List<SalesOrderDetail.PaymentRow> rows = held.rows().stream().map(row -> {
            Long change = row.getPaymentType() == PaymentType.ORDER_PAYMENT && row.getTenderedAmount() != null
                    ? row.getTenderedAmount() - row.getAmount() : null;
            return new SalesOrderDetail.PaymentRow(row.getId(), row.getPaymentType(), row.getPaymentMethod(),
                    held.amountOf(row), row.getTenderedAmount(), change, row.getTransactionReference(),
                    row.getStatus(), row.getRecordedBy(), names.get(row.getRecordedBy()), row.getRecordedAt(),
                    row.getVerifiedBy(), names.get(row.getVerifiedBy()), row.getVerifiedAt(),
                    row.getRejectionReason(), evidence.getOrDefault(row.getId(), List.of()));
        }).toList();
        SalesOrderDetail.Credit credit = order.getCreditDecision() == null ? null
                : new SalesOrderDetail.Credit(order.getCreditDecision(), order.getApprovedCreditAmount(),
                order.getCreditDecidedBy(), names.get(order.getCreditDecidedBy()), order.getCreditDecidedAt());
        SalesOrderDetail.Debt debt = receivables.findWithRemainingBySalesOrderId(order.getId()).stream()
                .findFirst().map(found -> {
                    Receivable receivable = (Receivable) found[0];
                    return new SalesOrderDetail.Debt(receivable.getId(), receivable.getOriginalAmount(),
                            ((Number) found[1]).longValue(), receivable.getDueDate(), receivable.getSettledAt());
                }).orElse(null);
        boolean confirmed = order.getStatus() == SalesOrderStatus.CONFIRMED;
        return new SalesOrderDetail.Money(total, held.verifiedPaid(), held.verifiedRefunded(), net,
                unfinished ? Math.max(0, total - net) : 0, unfinished ? Math.max(0, net - total) : 0,
                unfinished ? held.refundable() : 0, confirmed ? held.payable(total) : 0, held.unverifiedCount(),
                rows, credit, debt);
    }

    /** The picker's reasons for the lines still short since the last picking result. */
    private static Map<Long, String> shortReasons(List<AuditLog> steps, List<SalesOrderLine> lines) {
        Map<Long, String> reasons = new HashMap<>();
        steps.stream().filter(step -> SalesOrderHistory.PICKED.equals(step.getAction())).findFirst()
                .ifPresent(step -> SalesOrderHistory.lines(step.getNewData()).forEach(row -> {
                    if (row.get("short_reason") != null) {
                        reasons.put(SalesOrderHistory.number(row.get("product_id")).longValue(),
                                (String) row.get("short_reason"));
                    }
                }));
        lines.stream().filter(line -> line.getPickedQty() == null || line.getPickedQty() >= line.getRequestedQty())
                .forEach(line -> reasons.remove(line.getProductId()));
        return reasons;
    }

    /** Screen 8.1.3 "Lịch sử": creation, versions, picker assignments, picking and confirmations, newest first. */
    private static List<HistoryEntry> history(List<AuditLog> steps, List<WarehouseTask> assignments,
                                              Map<Long, String> names) {
        List<HistoryEntry> entries = new ArrayList<>();
        for (AuditLog step : steps) {
            Map<String, Object> data = step.getNewData() == null ? Map.of() : step.getNewData();
            Integer version = data.get("version") == null ? null
                    : SalesOrderHistory.number(data.get("version")).intValue();
            String action = step.getAction().substring("SALES_ORDER_".length());
            entries.add(new HistoryEntry(action, version, (String) data.get("cause"), step.getActorId(),
                    names.get(step.getActorId()), step.getOccurredAt()));
        }
        for (WarehouseTask task : assignments) {
            entries.add(new HistoryEntry("PICKER_ASSIGNED", null, null, task.getAssignedBy(),
                    names.get(task.getAssignedBy()), task.getAssignedAt()));
        }
        entries.sort(Comparator.comparing(HistoryEntry::occurredAt).reversed());
        return entries;
    }

    private Map<Long, String> names(Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        wanted.remove(null);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(wanted).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }

    private static Specification<SalesOrder> matching(String q, Long storeId, Long pickerId, SalesOrderView view,
                                                      LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(root.get("status").in(view.statuses()));
            if (storeId != null) {
                where.add(cb.equal(root.get("storeId"), storeId));
            }
            if (pickerId != null) {
                Subquery<Long> picking = query.subquery(Long.class);
                Root<WarehouseTask> task = picking.from(WarehouseTask.class);
                picking.select(task.get("salesOrderId")).where(
                        cb.equal(task.get("taskType"), TaskType.PICKING),
                        cb.equal(task.get("assignedTo"), pickerId),
                        task.get("status").in(WarehouseTaskStatus.ASSIGNED, WarehouseTaskStatus.RESULT_SUBMITTED));
                where.add(root.get("id").in(picking));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay()));
            }
            if (StringUtils.hasText(q)) {
                String needle = q.strip().toLowerCase(Locale.ROOT);
                String like = "%" + needle + "%";
                Predicate byCustomer = cb.or(cb.like(cb.lower(root.get("customerNameSnapshot")), like),
                        cb.like(root.get("customerPhoneSnapshot"), like));
                Long number = orderNumber(needle);
                where.add(number == null ? byCustomer : cb.or(cb.equal(root.get("id"), number), byCustomer));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** "12" or "#12" read as order number 12; anything else is no order number. */
    private static Long orderNumber(String needle) {
        String digits = needle.startsWith("#") ? needle.substring(1).strip() : needle;
        if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return Long.parseLong(digits);
    }

    /** How an order is named in notifications and messages: its number, which is its id (Report 3 entity). */
    private static String label(SalesOrder order) {
        return "#" + order.getId();
    }

    private static String money(long amount) {
        return NumberFormat.getIntegerInstance(VIETNAMESE).format(amount);
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG156");
    }

    private static <T> Map<Long, T> byId(Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
