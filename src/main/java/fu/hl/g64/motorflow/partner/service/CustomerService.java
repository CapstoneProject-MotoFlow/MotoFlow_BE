package fu.hl.g64.motorflow.partner.service;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.partner.dto.CustomerDetailResponse;
import fu.hl.g64.motorflow.partner.dto.CustomerRequest;
import fu.hl.g64.motorflow.partner.dto.CustomerResponse;
import fu.hl.g64.motorflow.partner.entity.CreditEligibilityEvent;
import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;
import fu.hl.g64.motorflow.partner.repository.CreditEligibilityEventRepository;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.UnmetDemand;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.sales.repository.UnmetDemandRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.store.service.StoreScope;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** UC-26 View Customer List, UC-27 Create Customer, UC-28 View Customer Detail, UC-29 Update Customer. */
@Service
@RequiredArgsConstructor
public class CustomerService {

    /** Rows of the order and payment lists on the detail screen; older ones are counted but not listed. */
    public static final int HISTORY_SIZE = 20;

    private final CustomerRepository customers;
    private final CreditEligibilityEventRepository creditEvents;
    private final ReceivableRepository receivables;
    private final PaymentRepository payments;
    private final SalesOrderRepository salesOrders;
    private final SalesOrderLineRepository salesOrderLines;
    private final UnmetDemandRepository unmetDemands;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final StoreScope storeScope;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    /**
     * By name. Every filter is optional; {@code q} matches name, phone number (spaces ignored) or customer number,
     * and {@code creditStatus = ELIGIBLE} lists the customers allowed to buy on credit.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(AuthenticatedUser user, String q, CreditStatus creditStatus, int page,
                                               int size) {
        Page<Customer> found = customers.findAll(matching(q, creditStatus),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("customerName", "id")));
        if (found.isEmpty()) {
            return PageResponse.of(found, List.of());
        }
        List<OpenDebt> debts = unsettledDebts(found.map(Customer::getId).getContent());
        return PageResponse.of(found, found.getContent().stream().map(c -> withDebts(c, debts, user)).toList());
    }

    @Transactional(readOnly = true)
    public CustomerDetailResponse detail(AuthenticatedUser user, long id) {
        Customer customer = find(id);
        Long storeId = storeScope.seesEveryStore(user) ? null : user.storeId();
        List<OpenDebt> debts = unsettledDebts(List.of(id));
        List<OpenDebt> visibleDebts = debts.stream()
                .filter(d -> storeId == null || d.receivable().getStoreId().equals(storeId)).toList();
        List<CreditEligibilityEvent> credit = creditEvents.findByCustomerIdOrderByDecidedAtDescIdDesc(id);

        Pageable newest = PageRequest.of(0, HISTORY_SIZE, Sort.by(Sort.Direction.DESC, "id"));
        Page<SalesOrder> orders = salesOrders.findByCustomer(id, storeId, newest);
        Page<Payment> paid = payments.findByCustomer(id, storeId, newest);
        List<UnmetDemand> waiting = unmetDemands.findWaitingByCustomer(id, storeId);

        Map<Long, String> storeNames = storeNames(Stream.concat(
                visibleDebts.stream().map(d -> d.receivable().getStoreId()), orders.stream().map(SalesOrder::getStoreId)));
        Map<Long, String> deciders = users.findAllById(credit.stream().map(CreditEligibilityEvent::getDecidedBy)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        Map<Long, Long> totals = orderTotals(orders.map(SalesOrder::getId).getContent());
        Map<Long, Product> productById = products.findAllById(waiting.stream().map(UnmetDemand::getProductId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        return new CustomerDetailResponse(withDebts(customer, debts, user),
                credit.stream().map(e -> new CustomerDetailResponse.CreditEvent(e.getEventType(), e.getReason(),
                        deciders.get(e.getDecidedBy()), e.getDecidedAt())).toList(),
                visibleDebts.stream().map(d -> {
                    Receivable r = d.receivable();
                    return new CustomerDetailResponse.DebtRow(r.getId(), r.getOriginType(), r.getStoreId(),
                            storeNames.get(r.getStoreId()), r.getSalesOrderId(), r.getOriginalAmount(), d.remaining(),
                            r.getRecognizedAt());
                }).toList(),
                orders.map(o -> new CustomerDetailResponse.OrderRow(o.getId(), o.getStoreId(), storeNames.get(o.getStoreId()),
                        o.getStatus(), o.getCompletionType(), totals.getOrDefault(o.getId(), 0L), o.getCreatedAt(),
                        o.getCompletedAt())).getContent(),
                orders.getTotalElements(),
                paid.map(p -> new CustomerDetailResponse.PaymentRow(p.getId(), p.getPaymentType(), p.getSalesOrderId(),
                        p.getReceivableId(), p.getAmount(), p.getPaymentMethod(), p.getStatus(), p.getRecordedAt()))
                        .getContent(),
                paid.getTotalElements(),
                waiting.stream().map(u -> {
                    Product product = productById.get(u.getProductId());
                    return new CustomerDetailResponse.UnmetDemandRow(u.getId(), u.getSalesOrderId(), u.getProductId(),
                            product.getSku(), product.getPartName(), u.getUnmetQty(), u.getRecordedAt());
                }).toList());
    }

    /** UC-27, also used while creating a sales order for a customer without a profile. */
    @Transactional
    public CustomerResponse create(AuthenticatedUser actor, CustomerRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        Customer customer = customers.save(Customer.create(request.customerName().strip(), blankToNull(request.phone()),
                blankToNull(request.address()), blankToNull(request.taxCode()), actor.id(), now));
        auditLog.save(AuditLog.record("CUSTOMER", customer.getId(), "CUSTOMER_CREATED", actor.id(), null, now));
        return withDebts(customer, List.of(), actor);
    }

    /** UC-29. Completed orders keep the details recorded on their version (customer snapshot). */
    @Transactional
    public CustomerResponse update(AuthenticatedUser actor, long id, CustomerRequest request) {
        Customer customer = find(id);
        LocalDateTime now = LocalDateTime.now(clock);
        customer.update(request.customerName().strip(), blankToNull(request.phone()), blankToNull(request.address()),
                blankToNull(request.taxCode()), now);
        auditLog.save(AuditLog.record("CUSTOMER", id, "CUSTOMER_UPDATED", actor.id(), null, now));
        return withDebts(customer, unsettledDebts(List.of(id)), actor);
    }

    /** An unsettled receivable and what is still owed on it. */
    private record OpenDebt(Receivable receivable, long remaining) {
    }

    private List<OpenDebt> unsettledDebts(Collection<Long> customerIds) {
        // A correction can never make the customer owe less than nothing.
        return receivables.findUnsettledWithRemaining(customerIds).stream()
                .map(row -> new OpenDebt((Receivable) row[0], Math.max(0, ((Number) row[1]).longValue())))
                .toList();
    }

    private static CustomerResponse withDebts(Customer customer, List<OpenDebt> debts, AuthenticatedUser viewer) {
        long total = 0;
        long atOwnStore = 0;
        for (OpenDebt debt : debts) {
            if (debt.receivable().getCustomerId().equals(customer.getId())) {
                total += debt.remaining();
                if (debt.receivable().getStoreId().equals(viewer.storeId())) {
                    atOwnStore += debt.remaining();
                }
            }
        }
        return CustomerResponse.from(customer, total, viewer.storeId() == null ? null : atOwnStore);
    }

    private Map<Long, Long> orderTotals(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> totals = new HashMap<>();
        salesOrderLines.currentTotals(orderIds).forEach(row -> totals.put((Long) row[0], ((Number) row[1]).longValue()));
        return totals;
    }

    private Map<Long, String> storeNames(Stream<Long> storeIds) {
        return stores.findAllById(storeIds.collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName));
    }

    private static Specification<Customer> matching(String q, CreditStatus creditStatus) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (StringUtils.hasText(q)) {
                String needle = q.strip().toLowerCase(Locale.ROOT);
                String digits = needle.replace(" ", "");
                List<Predicate> any = new ArrayList<>(List.of(
                        cb.like(cb.lower(root.get("customerName")), "%" + needle + "%"),
                        cb.like(cb.function("replace", String.class, root.get("phone"), cb.literal(" "), cb.literal("")),
                                "%" + digits + "%")));
                if (digits.matches("[0-9]{1,18}")) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(digits)));
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (creditStatus != null) {
                where.add(cb.equal(root.get("creditStatus"), creditStatus));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private Customer find(long id) {
        return customers.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG74"));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
