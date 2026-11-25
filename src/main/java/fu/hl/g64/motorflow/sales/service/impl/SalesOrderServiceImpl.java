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
import fu.hl.g64.motorflow.common.text.SearchText;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
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
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.sales.dto.ConfirmSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.ExpectedReadyDateRequest;
import fu.hl.g64.motorflow.sales.dto.ReleaseWaitingOrderRequest;
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
import fu.hl.g64.motorflow.sales.entity.SalesDepositApplication;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.DepositStatus;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.helper.OrderCollections;
import fu.hl.g64.motorflow.sales.helper.OrderDeposits;
import fu.hl.g64.motorflow.sales.helper.OrderPricing;
import fu.hl.g64.motorflow.sales.helper.OrderPricing.Priced;
import fu.hl.g64.motorflow.sales.helper.OrderPricing.Totals;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.sales.helper.SalesOrderHistory;
import fu.hl.g64.motorflow.sales.helper.WaitingOrderReadiness;
import fu.hl.g64.motorflow.sales.repository.SalesDepositApplicationRepository;
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
import java.time.format.DateTimeFormatter;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
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
    private static final String DECLINED_REASON = "Khách không chờ hàng";
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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
    private final StockBalanceRepository balances;
    private final OrderCollections collections;
    private final ReceivableRepository receivables;
    private final AttachmentService attachments;
    private final OrderDeposits deposits;
    private final SalesDepositApplicationRepository depositApplications;
    private final TransactionReferences references;
    private final WaitingOrderReadiness readiness;
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
        SalesOrderView view = search.view() != null ? search.view()
                : search.status() != null ? SalesOrderView.ALL : SalesOrderView.OPEN;
        Set<SalesOrderStatus> statuses = EnumSet.copyOf(view.statuses());
        if (search.status() != null) {
            statuses.retainAll(EnumSet.of(search.status()));
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        if (statuses.isEmpty()) {
            return PageResponse.of(Page.empty(pageable), List.of());
        }
        LocalDate from = search.createdFrom();
        LocalDate to = search.createdTo();
        if (!SalesOrderView.OPEN.statuses().containsAll(statuses)) {
            // Screen 8.1.1: only unfinished orders show every day; finished ones the chosen creation days, today by
            // default.
            LocalDate today = LocalDate.now(clock);
            to = to != null ? to : (from != null && from.isAfter(today) ? from : today);
            from = from != null ? from : to;
        }
        Specification<SalesOrder> where = matching(search.q(), storeId, pickerId, statuses, from, to);
        // BE-8: Staff see no deposit, so they cannot filter by it either.
        if (search.depositStatus() != null && pickerId == null) {
            // The deposit state is worked out from the money, so the orders are picked by id.
            LocalDate today = LocalDate.now(clock);
            DepositStatus wanted = search.depositStatus();
            Map<Long, DepositFigures> figures = depositFigures(orders.depositFigures(storeId));
            Set<Long> picked = figures.entrySet().stream()
                    .filter(entry -> (entry.getValue().status(today) == wanted) != (wanted == DepositStatus.NONE))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            if (wanted != DepositStatus.NONE && picked.isEmpty()) {
                return PageResponse.of(Page.empty(pageable), List.of());
            }
            where = where.and((root, query, cb) -> picked.isEmpty() ? cb.conjunction()
                    : wanted == DepositStatus.NONE ? cb.not(root.get("id").in(picked)) : root.get("id").in(picked));
        }
        Page<SalesOrder> found = orders.findAll(where, pageable);
        return PageResponse.of(found, summaries(user, found.getContent()));
    }

    // UC-54
    @Transactional(readOnly = true)
    @Override
    public SalesOrderDetail get(AuthenticatedUser user, long id) {
        SalesOrder order = orders.findById(id).filter(found -> !found.isDraft()).orElseThrow(SalesOrderServiceImpl::notFound);
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
        SalesOrder order = orders.findById(id).filter(found -> !found.isDraft()).orElseThrow(SalesOrderServiceImpl::notFound);
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
        Map<Long, SalesOrderPreview.Variant> variants = variants(store.getId(), parts.keySet(), now);

        List<SalesOrderPreview.Line> lines = new ArrayList<>();
        List<Priced> priced = new ArrayList<>();
        for (SalesOrderLineRequest line : request.lines()) {
            Product part = parts.get(line.productId());
            Long reference = prices.get(part.getId());
            Long actual = reference == null ? null : OrderPricing.actualUnitPrice(reference, rate);
            if (reference != null) {
                priced.add(new Priced(line.quantity(), reference, actual));
            }
            PickingGuide.Part held = stock.get(part.getId());
            int sellable = held.sellableQty();
            lines.add(new SalesOrderPreview.Line(part.getId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                    part.getStatus(), line.quantity(), reference, actual,
                    actual == null ? null : (long) line.quantity() * actual, sellable,
                    Math.max(0, line.quantity() - sellable), held.heldByOthersQty(),
                    Math.min(line.quantity(), sellable), variants.get(part.getId())));
        }
        Totals totals = OrderPricing.totals(priced);
        if (priced.size() == lines.size()) {
            checkRequestedMargin(seller, request.lines(), parts, totals, now);
        }
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

    /** GVHD 20/11 (PR 5): [part id → its dented-goods variant] for the parts whose variant the store can sell. */
    private Map<Long, SalesOrderPreview.Variant> variants(long storeId, Collection<Long> productIds,
                                                          LocalDateTime now) {
        List<Product> found = products.findByVariantOfProductIdIn(productIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = found.stream().map(Product::getId).toList();
        Map<Long, PickingGuide.Part> stock = guide.parts(storeId, ids, now.toLocalDate());
        Map<Long, Long> prices = pricing.retailPrices(ids, now);
        Map<Long, SalesOrderPreview.Variant> variants = new HashMap<>();
        for (Product variant : found) {
            int sellable = stock.get(variant.getId()).sellableQty();
            if (sellable > 0) {
                variants.put(variant.getVariantOfProductId(), new SalesOrderPreview.Variant(variant.getId(),
                        variant.getSku(), sellable, prices.get(variant.getId())));
            }
        }
        return variants;
    }

    // UC-53
    @Transactional
    @Override
    public SalesOrderDetail create(AuthenticatedUser seller, SalesOrderRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        Content content = content(seller, request, now);
        LocalDate expected = expectedReadyDate(request.expectedReadyDate(), now.toLocalDate());
        List<Share> shares = shares(content.store().getId(), content.customer().id() == null, request.lines(),
                now.toLocalDate());
        SalesOrder order = orders.save(SalesOrder.create(content.store().getId(), content.customer(),
                request.priceType(), content.rate(), text(request.note()), seller.id(), now));
        return openSplit(seller, order, shares, content, now, expected);
    }

    // PM 13/11: sales drafts
    @Transactional(readOnly = true)
    @Override
    public PageResponse<SalesOrderSummary> drafts(AuthenticatedUser seller, Long storeId, String q, int page,
                                                  int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Specification<SalesOrder> mine = matching(q, seller.role() == Role.OWNER ? storeId : null, null,
                EnumSet.of(SalesOrderStatus.DRAFT), null, null)
                .and((root, query, cb) -> cb.equal(root.get("createdBy"), seller.id()));
        Page<SalesOrder> found = orders.findAll(mine, pageable);
        return PageResponse.of(found, summaries(seller, found.getContent()));
    }

    @Transactional
    @Override
    public SalesOrderDetail createDraft(AuthenticatedUser seller, SalesOrderRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        Content content = content(seller, request, now);
        SalesOrder order = orders.save(SalesOrder.createDraft(content.store().getId(), content.customer(),
                request.priceType(), content.rate(), text(request.note()), seller.id(), now));
        saveLines(order, request, content);
        return detail(seller, order);
    }

    @Transactional(readOnly = true)
    @Override
    public SalesOrderDetail draft(AuthenticatedUser seller, long id) {
        return detail(seller, orders.findById(id).filter(found -> isOwnDraft(seller, found))
                .orElseThrow(SalesOrderServiceImpl::notFound));
    }

    @Transactional
    @Override
    public SalesOrderDetail updateDraft(AuthenticatedUser seller, long id, SalesOrderRequest request) {
        SalesOrder order = lockOwnDraft(seller, id);
        LocalDateTime now = LocalDateTime.now(clock);
        rewriteDraft(seller, order, request, now);
        return detail(seller, order);
    }

    @Transactional
    @Override
    public SalesOrderDetail submitDraft(AuthenticatedUser seller, long id, SalesOrderRequest request) {
        SalesOrder order = lockOwnDraft(seller, id);
        LocalDateTime now = LocalDateTime.now(clock);
        // UC-53 as for a new order: the prices in effect now (BR-18) and the discount threshold (BR-16). A refusal
        // rolls the whole transaction back, so the draft keeps what it had.
        Content content = rewriteDraft(seller, order, request, now);
        LocalDate expected = expectedReadyDate(request.expectedReadyDate(), now.toLocalDate());
        List<Share> shares = shares(content.store().getId(), content.customer().id() == null, request.lines(),
                now.toLocalDate());
        order.submit(now);
        // GVHD 20/11: the draft becomes the order sold now (or the waiting order if nothing can be sold now).
        orderLines.deleteAll(orderLines.findBySalesOrderIdOrderByLineNoAsc(id));
        orderLines.flush();
        return openSplit(seller, order, shares, content, now, expected);
    }

    /**
     * GVHD 20/11: one requested line split into what is sold now (held by the order), what waits on a waiting order
     * and what the customer declined (not stored).
     */
    private record Share(SalesOrderLineRequest line, int sellNow, int waitQty, int declinedQty) {
    }

    /**
     * GVHD 20/11 (BR-29, BR-33): how much of each line the store sells now, against the stock not held by other
     * open orders, read under the lock of the parts' stock rows so two sellers take turns. The server works it out
     * again and does not trust the client's figures: sellNow = min(nowQty, sellable); with {@code waitForRest} the
     * rest of the requested quantity waits, without it the rest is declined. MSG171 when nothing at all is sold or
     * waits. Góp ý UI 21/11: a walk-in customer (no profile) takes the whole quantity now and cannot buy more than
     * is in stock, MSG332.
     */
    private List<Share> shares(long storeId, boolean walkIn, List<SalesOrderLineRequest> lines, LocalDate today) {
        List<Long> ids = lines.stream().map(SalesOrderLineRequest::productId).sorted().toList();
        balances.findByStoreForUpdate(storeId, ids);
        Map<Long, PickingGuide.Part> stock = guide.parts(storeId, ids, today);
        Map<Long, Product> parts = byId(products.findAllById(ids), Product::getId);
        List<Share> shares = new ArrayList<>();
        List<String> lacking = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            SalesOrderLineRequest line = lines.get(i);
            int now = line.takenNow();
            if (now > line.quantity()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG305", i + 1, line.quantity());
            }
            if (now == 0 && !line.waits()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG306", i + 1);
            }
            int sellable = stock.get(line.productId()).sellableQty();
            if (walkIn) {
                // Góp ý UI 21/11 (review R-01, R-03): a walk-in customer takes the whole quantity now, so it must be
                // in stock; nothing waits and nothing is left as declined, whatever nowQty and waitForRest say.
                if (line.quantity() > sellable) {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG332", parts.get(line.productId()).getSku(),
                            line.quantity(), sellable);
                }
                shares.add(new Share(line, line.quantity(), 0, 0));
                continue;
            }
            int sellNow = Math.min(now, sellable);
            int waitQty = line.waits() ? line.quantity() - sellNow : 0;
            shares.add(new Share(line, sellNow, waitQty, line.quantity() - sellNow - waitQty));
            if (sellNow < now) {
                lacking.add("%s cần %d, còn %d".formatted(parts.get(line.productId()).getSku(), now, sellable));
            }
        }
        if (shares.stream().allMatch(share -> share.sellNow() == 0 && share.waitQty() == 0)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG171", String.join("; ", lacking));
        }
        return shares;
    }

    /**
     * GVHD 20/11: saves {@code order} with what is sold now, and what waits on a waiting order split from it; if
     * nothing is sold now, {@code order} itself waits. BR-16 is checked on the order that is sold now (the waiting
     * order is checked when released), or on the waiting order when it is all there is.
     */
    private SalesOrderDetail openSplit(AuthenticatedUser seller, SalesOrder order, List<Share> shares,
                                       Content content, LocalDateTime now, LocalDate expected) {
        // GVHD 24/11: what this order holds is no longer there for the waiting orders.
        readiness.storeChanged(order.getStoreId());
        List<Share> sold = shares.stream().filter(share -> share.sellNow() > 0).toList();
        List<Share> waiting = shares.stream().filter(share -> share.waitQty() > 0).toList();
        BigDecimal rate = content.rate();
        List<SalesOrderLineRequest> requested = shares.stream().map(Share::line).toList();
        if (order.getPriceType() == PriceType.RETAIL
                && requested.stream().anyMatch(line -> line.waits() && line.takenNow() == 0)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG335");
        }
        // BR-16 and BR-88 (Q2) on the order opened now: what is sold now, or the waiting order when nothing is. Units
        // declined are never sold and must not lift the margin; the waiting part is checked again on release.
        List<Share> openedNow = sold.isEmpty() ? waiting : sold;
        Map<Long, Integer> openedQty = openedNow.stream().collect(Collectors.toMap(share -> share.line().productId(),
                share -> sold.isEmpty() ? share.waitQty() : share.sellNow()));
        List<Priced> openedPrices = openedNow.stream().map(share -> priced(openedQty.get(share.line().productId()),
                content.prices().get(share.line().productId()), rate)).toList();
        checkPricing(rate, openedPrices, now);
        checkMargin(seller, content.parts(), openedQty, OrderPricing.totals(openedPrices), now);
        List<SalesOrderDetail.Declined> declined = shares.stream().filter(share -> share.declinedQty() > 0)
                .map(share -> new SalesOrderDetail.Declined(share.line().productId(), share.declinedQty())).toList();
        List<Share> refused = shares.stream().filter(share -> share.declinedQty() > 0).toList();
        if (sold.isEmpty()) {
            order.waitForStock();
            order.setExpectedReadyDate(expected, now);
            List<SalesOrderLine> lines = saveShares(order, waiting, Share::waitQty, content);
            SalesOrderDetail detail = opened(seller, order, lines, now);
            Long declinedId = declineRest(seller, order, refused, content, now);
            return detail.withSplit(new SalesOrderDetail.Split(null, order.getId(), declinedId, declined));
        }
        List<SalesOrderLine> lines = saveShares(order, sold, Share::sellNow, content);
        SalesOrderDetail detail = opened(seller, order, lines, now);
        Long waitingId = null;
        if (!waiting.isEmpty()) {
            SalesOrder rest = orders.save(SalesOrder.create(order.getStoreId(), customerOf(order),
                    order.getPriceType(), rate, order.getNote(), seller.id(), now));
            rest.waitForStock();
            rest.setExpectedReadyDate(expected, now);
            opened(seller, rest, saveShares(rest, waiting, Share::waitQty, content), now, order.getId());
            waitingId = rest.getId();
        }
        Long declinedId = declineRest(seller, order, refused, content, now);
        if (waitingId == null && declinedId == null) {
            return detail;
        }
        return detail.withSplit(new SalesOrderDetail.Split(order.getId(), waitingId, declinedId, declined));
    }

    /**
     * GVHD 20/11: what the customer declined is kept as an order of its own, cancelled at once ("Khách không chờ
     * hàng"), so the unmet-demand report (UC-59) shows it. It is split from {@code order}, holds nothing, is not
     * picked, notifies nobody and is not checked against the discount threshold (it is never sold).
     */
    private Long declineRest(AuthenticatedUser seller, SalesOrder order, List<Share> refused, Content content,
                             LocalDateTime now) {
        if (refused.isEmpty()) {
            return null;
        }
        SalesOrder gone = orders.save(SalesOrder.create(order.getStoreId(), customerOf(order), order.getPriceType(),
                content.rate(), order.getNote(), seller.id(), now));
        List<SalesOrderLine> lines = saveShares(gone, refused, Share::declinedQty, content);
        Map<String, Object> first = SalesOrderHistory.snapshot(1, null, gone, lines);
        first.put(SalesOrderHistory.SPLIT_FROM, order.getId());
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, gone.getId(), SalesOrderHistory.CREATED, seller.id(),
                null, null, first, now));
        gone.cancel(seller.id(), DECLINED_REASON, now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", null);
        data.put("refunded", 0);
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, gone.getId(), SalesOrderHistory.CANCELLED,
                seller.id(), DECLINED_REASON, null, data, now));
        return gone.getId();
    }

    private List<SalesOrderLine> saveShares(SalesOrder order, List<Share> shares,
                                            java.util.function.ToIntFunction<Share> quantity, Content content) {
        List<SalesOrderLine> lines = new ArrayList<>();
        for (int i = 0; i < shares.size(); i++) {
            Share share = shares.get(i);
            long reference = content.prices().get(share.line().productId());
            lines.add(SalesOrderLine.create(order.getId(), i + 1, share.line().productId(),
                    quantity.applyAsInt(share), reference, OrderPricing.actualUnitPrice(reference, content.rate())));
        }
        return orderLines.saveAll(lines);
    }

    private static SalesOrder.Customer customerOf(SalesOrder order) {
        return new SalesOrder.Customer(order.getCustomerId(), order.getCustomerNameSnapshot(),
                order.getCustomerPhoneSnapshot(), order.getCustomerAddressSnapshot());
    }

    @Transactional
    @Override
    public void deleteDraft(AuthenticatedUser seller, long id) {
        SalesOrder order = lockOwnDraft(seller, id);
        orderLines.deleteAll(orderLines.findBySalesOrderIdOrderByLineNoAsc(id));
        orderLines.flush();
        orders.delete(order);
    }

    /** Only its creator knows a draft exists: any other caller, and any id of a real order, gets MSG156. */
    private static boolean isOwnDraft(AuthenticatedUser seller, SalesOrder order) {
        return order.isDraft() && order.getCreatedBy() == seller.id();
    }

    private SalesOrder lockOwnDraft(AuthenticatedUser seller, long id) {
        return orders.findByIdForUpdate(id).filter(found -> isOwnDraft(seller, found))
                .orElseThrow(SalesOrderServiceImpl::notFound);
    }

    /** Replaces the draft's whole content with the request, at the prices in effect now. */
    private Content rewriteDraft(AuthenticatedUser seller, SalesOrder order, SalesOrderRequest request,
                                 LocalDateTime now) {
        Content content = content(seller, request, now);
        order.changeDraft(content.store().getId(), content.customer(), request.priceType(), content.rate(),
                text(request.note()), now);
        orderLines.deleteAll(orderLines.findBySalesOrderIdOrderByLineNoAsc(order.getId()));
        orderLines.flush();
        saveLines(order, request, content);
        return content;
    }

    /**
     * What every new order and draft is checked for (UC-53): the selling store, the customer, the discount rate
     * (BR-10, BR-12), the parts (MSG57, MSG158, MSG159) and a retail price in effect for each (MSG160). The discount
     * threshold (BR-16) is checked on the order sold now when it is opened, which a draft skips.
     */
    private Content content(AuthenticatedUser seller, SalesOrderRequest request, LocalDateTime now) {
        Store store = sellingStore(seller, request.storeId());
        SalesOrder.Customer customer = customer(request.customerId(), request.walkInName(), request.walkInPhone());
        BigDecimal rate = discountRate(request.priceType(), request.discountRate());
        Map<Long, Product> parts = checkLines(request.lines(), Set.of(), true);
        Map<Long, Long> prices = requirePrices(request.lines(), parts, now);
        List<Priced> priced = request.lines().stream()
                .map(line -> priced(line.quantity(), prices.get(line.productId()), rate)).toList();
        return new Content(store, customer, rate, prices, priced, parts);
    }

    private record Content(Store store, SalesOrder.Customer customer, BigDecimal rate, Map<Long, Long> prices,
                           List<Priced> priced, Map<Long, Product> parts) {
    }

    private List<SalesOrderLine> saveLines(SalesOrder order, SalesOrderRequest request, Content content) {
        List<SalesOrderLine> lines = new ArrayList<>();
        for (int i = 0; i < request.lines().size(); i++) {
            SalesOrderLineRequest line = request.lines().get(i);
            long reference = content.prices().get(line.productId());
            lines.add(SalesOrderLine.create(order.getId(), i + 1, line.productId(), line.quantity(), reference,
                    OrderPricing.actualUnitPrice(reference, content.rate())));
        }
        return orderLines.saveAll(lines);
    }

    /** UC-53 steps 9-10: version 1 is recorded and, when Owner sold it, the store's Branch Manager is told. */
    private SalesOrderDetail opened(AuthenticatedUser seller, SalesOrder order, List<SalesOrderLine> lines,
                                    LocalDateTime now) {
        return opened(seller, order, lines, now, null);
    }

    /** As above for an order split from {@code splitFrom} (GVHD 20/11): version 1 keeps where it came from. */
    private SalesOrderDetail opened(AuthenticatedUser seller, SalesOrder order, List<SalesOrderLine> lines,
                                    LocalDateTime now, Long splitFrom) {
        Map<String, Object> first = SalesOrderHistory.snapshot(1, null, order, lines);
        if (splitFrom != null) {
            first.put(SalesOrderHistory.SPLIT_FROM, splitFrom);
        }
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, order.getId(), SalesOrderHistory.CREATED, seller.id(),
                null, null, first, now));
        if (order.getStatus() == SalesOrderStatus.AWAITING_PICKING) {
            // A waiting order is not picked yet.
            notifyPicking(seller, order);
        }
        return detail(seller, order);
    }

    // UC-55
    @Transactional
    @Override
    public SalesOrderDetail update(AuthenticatedUser seller, long id, SalesOrderUpdateRequest request) {
        SalesOrder order = lockForSeller(seller, id);
        readiness.storeChanged(order.getStoreId());
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
        // The note is a memo outside the versions: changing only it saves no version and keeps the confirmation.
        LocalDateTime now = LocalDateTime.now(clock);
        order.changeNote(text(request.note()), now);
        if (!linesChanged && !customerChanged && !pricingChanged) {
            return detail(seller, order);
        }

        // BR-18, BR-36: the new version takes the retail prices in effect now.
        Map<Long, Long> prices = requirePrices(request.lines(), parts, now);
        List<Priced> revisedPrices = request.lines().stream()
                .map(line -> priced(line.quantity(), prices.get(line.productId()), rate)).toList();
        checkPricing(rate, revisedPrices, now);
        checkRequestedMargin(seller, request.lines(), parts, OrderPricing.totals(revisedPrices), now);

        if (!order.isWaitingForStock()) {
            // GVHD 20/11: more units on an order that holds its goods must be in stock, not held by others (MSG171);
            // a picked or confirmed order whose lines change goes back to holding the whole request, so every line
            // is checked. A waiting order is checked when released.
            boolean backToPicking = linesChanged && order.getStatus() != SalesOrderStatus.AWAITING_PICKING;
            checkMoreInStock(order, wanted, backToPicking ? Map.of() : before, parts, now.toLocalDate());
        }
        Map<String, Object> previous = SalesOrderHistory.snapshot(version, null, order, current);
        boolean waiting = order.isWaitingForStock();
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

        if (linesChanged && !waiting) {
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
        SalesOrder order = orders.findByIdForUpdate(id).filter(found -> !found.isDraft())
                .orElseThrow(SalesOrderServiceImpl::notFound);
        // BR-32: only someone ever given this order to pick learns what state it is in.
        boolean given = tasks.findHistory(TaskType.PICKING, id).stream()
                .anyMatch(task -> task.getAssignedTo() == picker.id());
        if (!given) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireUnfinished(order);
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
            // The picker's note is kept on every line: the reason when short, any remark when picked in full.
            row.put("short_reason", text(line.shortReason()));
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
        SalesOrder order = lockForSeller(seller, id);
        readiness.storeChanged(order.getStoreId());
        if (order.isWaitingForStock()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG308");
        }
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
            // What already went to a waiting order is no longer on the line (GVHD 20/11).
            int most = Math.min(target.getPickedQty(), target.getRequestedQty());
            if (line.deliveredQty() > most) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG169", parts.get(line.productId()).getSku(),
                        most);
            }
            delivered.put(line.productId(), line.deliveredQty());
        }
        if (delivered.values().stream().allMatch(quantity -> quantity == 0)) {
            // BR-14: the reference total of the delivered goods must be greater than zero.
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG170");
        }
        Map<Long, Integer> shortWaits = shortWaits(order, request, byProduct, delivered);
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
        data.put("lines", lines.stream().map(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.getProductId());
            row.put("delivered_qty", line.getDeliveredQty());
            return row;
        }).toList());
        Long waitingId = null;
        if (!shortWaits.isEmpty()) {
            SalesOrder rest = orders.save(SalesOrder.create(order.getStoreId(), customerOf(order),
                    order.getPriceType(), rate, order.getNote(), seller.id(), now));
            rest.waitForStock();
            List<SalesOrderLine> restLines = new ArrayList<>();
            for (SalesOrderLine line : lines) {
                Integer quantity = shortWaits.get(line.getProductId());
                if (quantity != null) {
                    long reference = prices.get(line.getProductId());
                    restLines.add(SalesOrderLine.create(rest.getId(), restLines.size() + 1, line.getProductId(),
                            quantity, reference, OrderPricing.actualUnitPrice(reference, rate)));
                }
            }
            opened(seller, rest, orderLines.saveAll(restLines), now, id);
            waitingId = rest.getId();
            data.put(SalesOrderHistory.SHORT_WAITING_ORDER, waitingId);
        }
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.CONFIRMED, seller.id(), null,
                null, data, now));
        SalesOrderDetail detail = detail(seller, order);
        return waitingId == null ? detail
                : detail.withSplit(new SalesOrderDetail.Split(id, waitingId, null, List.of()));
    }

    /**
     * GVHD 24/11 (A6): [productId → quantity] the customer waits for of what the warehouse could not pick, from the
     * lines marked {@code waitForShort} that deliver less than requested. Only for a customer with a profile (MSG351);
     * a retail order cannot wait for a whole line (MSG335); once per order (MSG353).
     */
    private Map<Long, Integer> shortWaits(SalesOrder order, ConfirmSalesOrderRequest request,
                                          Map<Long, SalesOrderLine> byProduct, Map<Long, Integer> delivered) {
        Map<Long, Integer> waits = new LinkedHashMap<>();
        boolean asked = false;
        for (ConfirmSalesOrderRequest.Line line : request.lines()) {
            SalesOrderLine target = byProduct.get(line.productId());
            // Only what the warehouse could not pick; what was picked but not taken is given up (review 25/11).
            int picked = Math.min(target.getPickedQty(), target.getRequestedQty());
            int missing = target.getRequestedQty() - picked;
            asked |= line.waits();
            if (line.waits() && missing > 0) {
                if (order.getPriceType() == PriceType.RETAIL && delivered.get(line.productId()) == 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG335");
                }
                waits.put(line.productId(), missing);
            }
        }
        if (!asked) {
            return waits;
        }
        if (!waits.isEmpty() && order.getCustomerId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG351");
        }
        auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(SalesOrderHistory.ENTITY,
                        order.getId(), List.of(SalesOrderHistory.CONFIRMED)).stream()
                .map(step -> step.getNewData().get(SalesOrderHistory.SHORT_WAITING_ORDER))
                .filter(Objects::nonNull).findFirst().ifPresent(waiting -> {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG353", String.valueOf(waiting));
                });
        return waits;
    }

    // GVHD 20/11
    @Transactional
    @Override
    public SalesOrderDetail release(AuthenticatedUser seller, long id, ReleaseWaitingOrderRequest request) {
        SalesOrder order = lockForSeller(seller, id);
        readiness.storeChanged(order.getStoreId());
        if (!order.isWaitingForStock()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG307", id);
        }
        // PM 21/11 (BE-5): after its last day the deposit waits for the Branch Manager's forfeit, not a release.
        OrderDeposits.State deposit = deposits.of(order, collections.of(id));
        if (deposit.status() == DepositStatus.EXPIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG321", order.getDepositExpiresOn().format(DAY));
        }
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(id);
        Map<Long, SalesOrderLine> byId = byId(lines, SalesOrderLine::getId);
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        for (int i = 0; i < request.lines().size(); i++) {
            ReleaseWaitingOrderRequest.Line line = request.lines().get(i);
            SalesOrderLine target = byId.get(line.lineId());
            if (target == null || wanted.containsKey(line.lineId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG166", i + 1);
            }
            if (line.nowQty() > target.getRequestedQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG305", i + 1, target.getRequestedQty());
            }
            wanted.put(line.lineId(), line.nowQty());
        }
        if (wanted.values().stream().allMatch(quantity -> quantity == 0)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> ids = lines.stream().map(SalesOrderLine::getProductId).sorted().toList();
        balances.findByStoreForUpdate(order.getStoreId(), ids);
        Map<Long, PickingGuide.Part> stock = guide.parts(order.getStoreId(), ids, now.toLocalDate());
        Map<Long, Product> parts = byId(products.findAllById(ids), Product::getId);
        Map<Long, Integer> released = new LinkedHashMap<>();
        wanted.forEach((lineId, quantity) -> released.put(lineId,
                Math.min(quantity, stock.get(byId.get(lineId).getProductId()).sellableQty())));
        if (released.values().stream().allMatch(quantity -> quantity == 0)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG171", wanted.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> "%s cần %d, còn %d".formatted(parts.get(byId.get(entry.getKey()).getProductId())
                            .getSku(), entry.getValue(), stock.get(byId.get(entry.getKey()).getProductId())
                            .sellableQty()))
                    .collect(Collectors.joining("; ")));
        }
        // BR-18: what is sold now takes the retail prices in effect, as a submitted draft does.
        // Only what is released needs a retail price in effect; a line that stays waiting may be unpriced.
        Map<Long, Long> prices = requireLinePrices(lines.stream()
                .filter(line -> released.getOrDefault(line.getId(), 0) > 0).toList(), parts, now);
        BigDecimal rate = order.getDiscountRate();
        boolean whole = lines.stream().allMatch(line -> released.getOrDefault(line.getId(), 0)
                == line.getRequestedQty());
        int version = currentVersion(id);
        Map<String, Object> previous = SalesOrderHistory.snapshot(version, null, order, lines);
        Map<Long, Integer> releasedByProduct = lines.stream().collect(Collectors.toMap(
                SalesOrderLine::getProductId, line -> released.getOrDefault(line.getId(), 0)));
        if (whole) {
            List<Priced> all = lines.stream().map(line -> priced(line.getRequestedQty(),
                    prices.get(line.getProductId()), rate)).toList();
            checkPricing(rate, all, now);
            Totals releaseTotals = OrderPricing.totals(all);
            checkMargin(seller, parts, releasedByProduct, releaseTotals, now);
            DepositUse use = depositUse(deposit, releaseTotals.total(), true, request);
            lines.forEach(line -> line.reprice(prices.get(line.getProductId()),
                    OrderPricing.actualUnitPrice(prices.get(line.getProductId()), rate)));
            order.release(now);
            orderLines.flush();
            applyDeposit(seller, id, id, use, request.depositRefund(), now);
            auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.VERSIONED, seller.id(),
                    null, previous, SalesOrderHistory.snapshot(version + 1, SalesOrderHistory.CAUSE_RELEASE, order,
                            lines), now));
            notifyPicking(seller, order);
            return detail(seller, order).withSplit(new SalesOrderDetail.Split(id, null, null, List.of()));
        }
        List<SalesOrderLine> taken = lines.stream().filter(line -> released.getOrDefault(line.getId(), 0) > 0)
                .toList();
        List<Priced> nowTaken = taken.stream().map(line -> priced(released.get(line.getId()),
                prices.get(line.getProductId()), rate)).toList();
        checkPricing(rate, nowTaken, now);
        Totals releaseTotals = OrderPricing.totals(nowTaken);
        checkMargin(seller, parts, releasedByProduct, releaseTotals, now);
        DepositUse use = depositUse(deposit, releaseTotals.total(), false, request);
        SalesOrder sold = orders.save(SalesOrder.create(order.getStoreId(), customerOf(order), order.getPriceType(),
                rate, order.getNote(), seller.id(), now));
        sold.soldFromWaitingOrder();
        List<SalesOrderLine> soldLines = new ArrayList<>();
        for (int i = 0; i < taken.size(); i++) {
            SalesOrderLine line = taken.get(i);
            long reference = prices.get(line.getProductId());
            soldLines.add(SalesOrderLine.create(sold.getId(), i + 1, line.getProductId(), released.get(line.getId()),
                    reference, OrderPricing.actualUnitPrice(reference, rate)));
        }
        orderLines.saveAll(soldLines);
        applyDeposit(seller, id, sold.getId(), use, null, now);
        List<SalesOrderLine> gone = taken.stream().filter(line -> released.get(line.getId())
                == line.getRequestedQty()).toList();
        taken.stream().filter(line -> !gone.contains(line))
                .forEach(line -> line.keepWaiting(line.getRequestedQty() - released.get(line.getId())));
        orderLines.deleteAll(gone);
        orderLines.flush();
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.VERSIONED, seller.id(), null,
                previous, SalesOrderHistory.snapshot(version + 1, SalesOrderHistory.CAUSE_RELEASE, order,
                        orderLines.findBySalesOrderIdOrderByLineNoAsc(id)), now));
        return opened(seller, sold, soldLines, now, id)
                .withSplit(new SalesOrderDetail.Split(sold.getId(), id, null, List.of()));
    }

    // GVHD 24/11
    @Transactional
    @Override
    public SalesOrderDetail changeExpectedReadyDate(AuthenticatedUser seller, long id,
                                                    ExpectedReadyDateRequest request) {
        SalesOrder order = lockForSeller(seller, id);
        if (!order.isWaitingForStock()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG352", id);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate next = expectedReadyDate(request.expectedReadyDate(), now.toLocalDate());
        LocalDate old = order.getExpectedReadyDate();
        if (!Objects.equals(old, next)) {
            order.setExpectedReadyDate(next, now);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("expected_ready_date", old == null ? null : old.toString());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("expected_ready_date", next == null ? null : next.toString());
            auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, id, SalesOrderHistory.EXPECTED_READY_DATE_CHANGED,
                    seller.id(), null, before, after, now));
        }
        return detail(seller, order);
    }

    /** GVHD 24/11: the expected ready date of a waiting order is today or later (MSG349); null when not given. */
    private static LocalDate expectedReadyDate(LocalDate date, LocalDate today) {
        if (date != null && date.isBefore(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG349");
        }
        return date;
    }

    /** What a release puts of the deposit into the order it opens, and what it gives back of it. */
    private record DepositUse(long applied, long refunded) {
    }

    /**
     * A partial release defaults to the most it can apply; older callers can still specify an amount (MSG327).
     * The last one puts in all it can (MSG326), gives the rest back (MSG328) and needs every deposit verified (MSG325),
     * so nothing of the deposit is left on the waiting order.
     */
    private static DepositUse depositUse(OrderDeposits.State deposit, long total, boolean last,
                                         ReleaseWaitingOrderRequest request) {
        long most = Math.min(deposit.remaining(), total);
        if (!last) {
            long amount = request.depositAmount() == null ? most : request.depositAmount();
            if (amount > most) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG327", money(most));
            }
            return new DepositUse(amount, 0);
        }
        if (deposit.unverified() > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG325", money(deposit.unverified()));
        }
        if (request.depositAmount() != null && request.depositAmount() != most) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG326", money(most));
        }
        long surplus = deposit.remaining() - most;
        if (surplus > 0 && request.depositRefund() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG328", money(surplus));
        }
        return new DepositUse(most, surplus);
    }

    /** Records the deposit the release put into {@code salesOrderId} and the part given back on the waiting order. */
    private void applyDeposit(AuthenticatedUser seller, long waitingId, long salesOrderId, DepositUse use,
                              ReleaseWaitingOrderRequest.DepositRefund refund, LocalDateTime now) {
        if (use.applied() > 0) {
            depositApplications.save(SalesDepositApplication.of(waitingId, salesOrderId, use.applied(), seller.id(),
                    now));
        }
        if (use.refunded() > 0) {
            String reference = TransactionReferences.clean(refund.transactionReference());
            if (refund.paymentMethod() == PaymentMethod.BANK_TRANSFER && reference == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG154");
            }
            references.requireUnused(reference);
            references.save(Payment.depositRefund(waitingId, use.refunded(), refund.paymentMethod(), reference,
                    seller.id(), now));
        }
    }

    /** UC-86: when the Owner sold it, the store's Branch Manager is told to give the picking to someone. */
    private void notifyPicking(AuthenticatedUser seller, SalesOrder order) {
        if (seller.role() == Role.OWNER) {
            notifications.notifyStoreManagers(order.getStoreId(), "SALES_ORDER_CREATED",
                    "Có đơn bán mới %s cần giao việc lấy hàng.".formatted(label(order)), REF_TYPE, order.getId());
        }
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

    /**
     * The order, locked until the end of the transaction, for its seller (BR-03), unless it is completed or cancelled
     * (MSG164). The seller is checked first, so another store learns nothing of the order's state.
     */
    private SalesOrder lockForSeller(AuthenticatedUser seller, long id) {
        SalesOrder order = orders.findByIdForUpdate(id).filter(found -> !found.isDraft())
                .orElseThrow(SalesOrderServiceImpl::notFound);
        requireSeller(seller, order);
        return requireUnfinished(order);
    }

    private static SalesOrder requireUnfinished(SalesOrder order) {
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

    /**
     * GVHD 20/11: each part asked for in more units than before (or newly added) must be sellable in the new quantity,
     * leaving out what other open orders hold (MSG171 listing them).
     */
    private void checkMoreInStock(SalesOrder order, Map<Long, Integer> wanted, Map<Long, Integer> before,
                                  Map<Long, Product> parts, LocalDate today) {
        Set<Long> more = wanted.entrySet().stream()
                .filter(entry -> entry.getValue() > before.getOrDefault(entry.getKey(), 0))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (more.isEmpty()) {
            return;
        }
        balances.findByStoreForUpdate(order.getStoreId(), more.stream().sorted().toList());
        Map<Long, PickingGuide.Part> stock = guide.parts(order.getStoreId(), more, today, order.getId());
        String lacking = more.stream().sorted().filter(id -> wanted.get(id) > stock.get(id).sellableQty())
                .map(id -> "%s cần %d, còn %d".formatted(parts.get(id).getSku(), wanted.get(id),
                        stock.get(id).sellableQty()))
                .collect(Collectors.joining("; "));
        if (!lacking.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG171", lacking);
        }
    }

    /** UC-58 step 6, BR-31, BR-76: the store holds enough unexpired available goods for every delivered line. */
    private void checkStock(SalesOrder order, List<SalesOrderLine> lines, Map<Long, Product> parts,
                            Map<Long, Integer> delivered, LocalDate today) {
        Map<Long, PickingGuide.Part> stock = guide.parts(order.getStoreId(), parts.keySet(), today, order.getId());
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
     * BR-88: order total ≥ the goods under review at the moving average cost × (1 + minimum margin). Only Owner learns the
     * amount (MSG173, BR-72); a Branch Manager is told the price is too low (MSG172).
     */
    private void checkRequestedMargin(AuthenticatedUser seller, List<SalesOrderLineRequest> lines,
                                      Map<Long, Product> parts, Totals totals, LocalDateTime now) {
        Map<Long, Integer> quantities = lines.stream().collect(Collectors.toMap(
                SalesOrderLineRequest::productId, SalesOrderLineRequest::quantity));
        checkMargin(seller, parts, quantities, totals, now);
    }

    private void checkMargin(AuthenticatedUser seller, Map<Long, Product> parts, Map<Long, Integer> quantities,
                             Totals totals, LocalDateTime now) {
        BigDecimal cost = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            BigDecimal average = parts.get(entry.getKey()).getMovingAvgCost();
            if (average != null) {
                cost = cost.add(average.multiply(BigDecimal.valueOf(entry.getValue())));
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
        List<Long> withDeposit = page.stream().filter(order -> order.getDepositExpiresOn() != null)
                .map(SalesOrder::getId).toList();
        Map<Long, DepositFigures> deposited = staff || withDeposit.isEmpty() ? Map.of()
                : depositFigures(orders.depositFiguresOf(withDeposit));
        LocalDate today = LocalDate.now(clock);

        return page.stream().map(order -> {
            WarehouseTask picking = pickings.get(order.getId());
            DepositFigures deposit = deposited.get(order.getId());
            return new SalesOrderSummary(order.getId(), 1 + versions.getOrDefault(order.getId(), 0L).intValue(),
                    order.getStatus(), order.getStoreId(), storeNames.get(order.getStoreId()), order.getCustomerId(),
                    order.getCustomerNameSnapshot(), staff ? null : order.getCustomerPhoneSnapshot(),
                    order.getCreatedAt(), staff ? null : totals.getOrDefault(order.getId(), 0L),
                    picking == null ? null : picking.getAssignedTo(),
                    picking == null ? null : names.get(picking.getAssignedTo()),
                    nextAction(user, order, picking, deposit == null ? null : deposit.status(today)),
                    deposit == null ? null : deposit.status(today),
                    deposit == null ? null : deposit.remaining(), deposit == null ? null : deposit.expiresOn());
        }).toList();
    }

    /** PM 21/11 (BE-8): the deposit of an order in the list, read as {@link OrderDeposits} reads it. */
    private record DepositFigures(LocalDate expiresOn, boolean forfeited, long total, long refunded, long applied) {

        long remaining() {
            return forfeited ? 0 : Math.max(0, total - applied - refunded);
        }

        DepositStatus status(LocalDate today) {
            return OrderDeposits.status(forfeited, total, remaining(), expiresOn, today);
        }
    }

    private static Map<Long, DepositFigures> depositFigures(List<Object[]> rows) {
        Map<Long, DepositFigures> figures = new HashMap<>();
        for (Object[] row : rows) {
            figures.put(((Number) row[0]).longValue(), new DepositFigures(day(row[1]), (Boolean) row[2],
                    ((Number) row[3]).longValue(), ((Number) row[4]).longValue(), ((Number) row[5]).longValue()));
        }
        return figures;
    }

    private static LocalDate day(Object value) {
        return value instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) value;
    }

    private static NextAction nextAction(AuthenticatedUser user, SalesOrder order, WarehouseTask picking,
                                          DepositStatus depositStatus) {
        if (picking != null && picking.getAssignedTo() == user.id()
                && picking.getStatus() == WarehouseTaskStatus.ASSIGNED) {
            return NextAction.RECORD_PICKING;
        }
        if (!isSeller(user, order)) {
            return null;
        }
        return switch (order.getStatus()) {
            case DRAFT -> null;
            case AWAITING_STOCK -> depositStatus == DepositStatus.EXPIRED ? null : NextAction.RELEASE;
            // GVHD 20/11: the Owner sells like a Branch Manager, so they give the picking too.
            case AWAITING_PICKING -> picking == null ? NextAction.ASSIGN_PICKER : null;
            // K1 (PM 19/11): the seller confirms, even a Branch Manager who picked the goods themself.
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
                ? guide.parts(order.getStoreId(), productIds, now.toLocalDate(), id) : Map.of();
        Map<Long, String> reasons = shortReasons(steps, lines);
        boolean draft = order.isDraft();
        OrderCollections.Money collected = staff || draft ? null : collections.of(id);
        OrderDeposits.State deposit = collected == null ? null : deposits.of(order, collected);
        Set<Long> people = new HashSet<>();
        people.add(order.getCreatedBy());
        people.add(order.getConfirmedBy());
        people.add(order.getCreditDecidedBy());
        people.add(order.getCompletedBy());
        people.add(order.getCancelledBy());
        people.add(order.getDepositForfeitedBy());
        if (deposit != null) {
            deposit.changes().forEach(change -> people.add(change.getChangedBy()));
        }
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
                    held == null ? List.of() : held.pickFirst(needed), held == null ? 0 : held.heldByOthersQty()));
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
        // A draft is only edited, submitted or deleted by its creator (PM 13/11).
        boolean waiting = order.isWaitingForStock();
        // A date left by a rejected deposit (nothing recorded) does not count as expired.
        boolean depositHolds = waiting && deposit != null && deposit.status() != DepositStatus.EXPIRED
                && (deposit.recorded() == 0 || !deposits.expired(order));
        // Owner, or the Branch Manager of this store: the same people who record a deposit.
        boolean storeManager = user.role() == Role.OWNER
                || (user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId()));
        Actions actions = draft ? new Actions(true, false, false, false, false, false, false, false, false, false,
                false, false, false, false)
                : new Actions(seller && unfinished,
                seller && order.getStatus() == SalesOrderStatus.AWAITING_PICKING,
                holdsTask && ((order.getStatus() == SalesOrderStatus.AWAITING_PICKING
                        && taskStatus == WarehouseTaskStatus.ASSIGNED)
                        || (order.getStatus() == SalesOrderStatus.PICKED
                        && taskStatus == WarehouseTaskStatus.RESULT_SUBMITTED)),
                // K1 (PM 19/11): a Branch Manager selling alone confirms what they picked themself; the Owner too
                // (GVHD 20/11). A seller is never Staff.
                seller && (order.getStatus() == SalesOrderStatus.PICKED || confirmedNow),
                seller && confirmedNow && money.payableNow() > 0,
                // A waiting order gives deposit back only through its last release (PM 21/11).
                seller && unfinished && !waiting && money.refundable() > 0,
                seller && confirmedNow && settled && creditAllowed && money.remainingPayable() > 0
                        && money.overpaidAmount() == 0,
                seller && confirmedNow && settled && money.remainingPayable() == 0 && money.overpaidAmount() == 0,
                seller && unfinished,
                seller && waiting && (deposit == null || deposit.status() != DepositStatus.EXPIRED),
                seller && depositHolds && order.getPriceType() == PriceType.WHOLESALE
                        && order.getCustomerId() != null,
                storeManager && depositHolds && deposit.total() > 0,
                storeManager && waiting && deposit != null && deposit.status() == DepositStatus.EXPIRED,
                seller && unfinished && waiting && deposit != null && deposit.remaining() > 0);
        Closing closing = staff ? null : new Closing(order.getCompletionType(), order.getCompletedBy(),
                names.get(order.getCompletedBy()), order.getCompletedAt(), order.getCancelledBy(),
                names.get(order.getCancelledBy()), order.getCancelledAt(), order.getCancellationReason());

        List<HistoryEntry> history = staff || draft ? List.of() : history(steps, assignments, names);
        String storeName = stores.findById(order.getStoreId()).map(Store::getStoreName).orElse(null);
        return new SalesOrderDetail(id, version, order.getStatus(), order.getStoreId(), storeName,
                order.getCustomerId(), order.getCustomerNameSnapshot(),
                staff ? null : order.getCustomerPhoneSnapshot(), staff ? null : order.getCustomerAddressSnapshot(),
                order.getNote(), order.getPriceType(), staff ? null : order.getDiscountRate(), picker,
                order.getCreatedBy(), names.get(order.getCreatedBy()), order.getCreatedAt(), order.getUpdatedAt(),
                order.getConfirmedBy(), names.get(order.getConfirmedBy()), order.getConfirmedAt(),
                delivered ? "DELIVERED" : "REQUESTED", staff ? null : totals.referenceTotal(),
                staff ? null : totals.discountAmount(), staff ? null : totals.total(),
                staff ? null : totals.invoiceDiscountRate(), staff ? null : pricing.discountThreshold(now), money,
                closing, rows, actions, history, splitFrom(steps), null, deposit(order, deposit, names),
                order.isWaitedForStock(), order.getExpectedReadyDate());
    }

    /** PM 21/11: the deposit block of the detail, null for Staff and for a draft. */
    private static SalesOrderDetail.Deposit deposit(SalesOrder order, OrderDeposits.State state,
                                                    Map<Long, String> names) {
        if (state == null) {
            return null;
        }
        List<SalesOrderDetail.ExpiryChange> changes = state.changes().stream()
                .map(change -> new SalesOrderDetail.ExpiryChange(change.getOldDate(), change.getNewDate(),
                        change.getChangedBy(), names.get(change.getChangedBy()), change.getChangedAt()))
                .toList();
        return new SalesOrderDetail.Deposit(state.total(), state.unverified(), state.applied(), state.refunded(),
                state.remaining(), state.expiresOn(), state.status(), order.getDepositForfeitedAmount(),
                order.getDepositForfeitedAt(),
                order.getDepositForfeitedBy(), names.get(order.getDepositForfeitedBy()), changes);
    }

    /** GVHD 20/11: the order this one was split from ("tách từ BH-xxx"), kept in its version 1 in audit_log. */
    private static Long splitFrom(List<AuditLog> steps) {
        return steps.stream().filter(step -> SalesOrderHistory.CREATED.equals(step.getAction()))
                .map(step -> step.getNewData().get(SalesOrderHistory.SPLIT_FROM)).filter(Objects::nonNull)
                .map(value -> SalesOrderHistory.number(value).longValue()).findFirst().orElse(null);
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
                rows, credit, debt, held.depositApplied());
    }

    /** The picker's note on each line of the last picking result: why it is short, or any remark when picked in full. */
    private static Map<Long, String> shortReasons(List<AuditLog> steps, List<SalesOrderLine> lines) {
        Map<Long, String> reasons = new HashMap<>();
        steps.stream().filter(step -> SalesOrderHistory.PICKED.equals(step.getAction())).findFirst()
                .ifPresent(step -> SalesOrderHistory.lines(step.getNewData()).forEach(row -> {
                    if (row.get("short_reason") != null) {
                        reasons.put(SalesOrderHistory.number(row.get("product_id")).longValue(),
                                (String) row.get("short_reason"));
                    }
                }));
        // A line not picked yet (new content since that result) has no note.
        lines.stream().filter(line -> line.getPickedQty() == null).forEach(line -> reasons.remove(line.getProductId()));
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

    private static Specification<SalesOrder> matching(String q, Long storeId, Long pickerId,
                                                      Set<SalesOrderStatus> statuses, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(root.get("status").in(statuses));
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
                String like = SearchText.likePattern(q);
                Predicate byCustomer = cb.or(SearchText.like(cb, root.get("customerNameSnapshot"), like),
                        cb.like(root.get("customerPhoneSnapshot"), like, '\\'));
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
