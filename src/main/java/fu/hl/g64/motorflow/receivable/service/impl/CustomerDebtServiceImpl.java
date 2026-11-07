package fu.hl.g64.motorflow.receivable.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.dto.CustomerDebtsResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentPreviewRequest;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentPreviewResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentRequest;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtStatus;
import fu.hl.g64.motorflow.receivable.dto.DebtorResponse;
import fu.hl.g64.motorflow.receivable.dto.OpeningDebtRequest;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.PaymentAllocation;
import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Allocation;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Debt;
import fu.hl.g64.motorflow.receivable.repository.PaymentAllocationRepository;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.receivable.service.CustomerDebtService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CustomerDebtServiceImpl implements CustomerDebtService {

    private final CustomerRepository customers;
    private final ReceivableRepository receivables;
    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final StoreScope storeScope;
    private final AttachmentService attachments;
    private final AuditLogRepository auditLog;
    private final DebtLedger ledger;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public PageResponse<DebtorResponse> debtors(AuthenticatedUser user, Long storeId, String q, boolean overdueOnly,
                                                int page, int size) {
        Long scope = scopeOf(user, storeId);
        Map<Long, List<Debt>> owedHere = ledger.owedAt(scope).stream()
                .collect(Collectors.groupingBy(debt -> debt.receivable().getCustomerId(), LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, Long> owedToChain = scope == null ? Map.of() : ledger.owedByCustomer(owedHere.keySet());
        LocalDate today = ledger.today();

        List<DebtorResponse> rows = new ArrayList<>();
        for (Customer customer : customers.findAllById(owedHere.keySet())) {
            if (!matches(customer, q)) {
                continue;
            }
            List<Debt> debts = owedHere.get(customer.getId());
            long debt = debts.stream().mapToLong(Debt::remaining).sum();
            long overdue = debts.stream().filter(d -> d.overdue(today)).mapToLong(Debt::remaining).sum();
            if (overdueOnly && overdue == 0) {
                continue;
            }
            LocalDate earliestDue = debts.stream().map(d -> d.receivable().getDueDate()).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            rows.add(new DebtorResponse(customer.getId(), customer.getCustomerName(), customer.getPhone(),
                    customer.getCreditStatus(), scope == null ? debt : owedToChain.getOrDefault(customer.getId(), debt),
                    debt, overdue, debts.size(), earliestDue));
        }
        rows.sort(Comparator.comparingLong(DebtorResponse::overdueDebt).reversed()
                .thenComparing(Comparator.comparingLong(DebtorResponse::debt).reversed())
                .thenComparing(DebtorResponse::customerName)
                .thenComparingLong(DebtorResponse::customerId));

        int pageSize = Math.clamp(size, 1, 100);
        int pageNumber = Math.max(page, 0);
        int from = (int) Math.min((long) pageNumber * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        return new PageResponse<>(rows.subList(from, to), rows.size(), pageNumber, pageSize);
    }

    @Transactional(readOnly = true)
    @Override
    public CustomerDebtsResponse debts(AuthenticatedUser user, long customerId, Long storeId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG74"));
        Long scope = scopeOf(user, storeId);
        List<Debt> debts = ledger.debtsOf(customerId, scope);
        Map<Long, Debt> byId = debts.stream().collect(Collectors.toMap(Debt::id, Function.identity()));
        LocalDate today = ledger.today();

        // Debt payments of these debts; each was collected at one store, so all its allocations are listed here.
        Set<Long> paymentIds = allocations.findByReceivableIdIn(byId.keySet()).stream()
                .map(PaymentAllocation::getPaymentId).collect(Collectors.toSet());
        List<Payment> paid = payments.findAllById(paymentIds).stream()
                .filter(p -> p.getPaymentType() == PaymentType.DEBT_PAYMENT)
                .sorted(Comparator.comparing(Payment::getRecordedAt).thenComparing(Payment::getId).reversed())
                .toList();
        Map<Long, List<PaymentAllocation>> split = allocations.findByPaymentIdIn(paymentIds).stream()
                .collect(Collectors.groupingBy(PaymentAllocation::getPaymentId));

        Map<Long, String> storeNames = storeNames(Stream.concat(Stream.ofNullable(scope),
                debts.stream().map(d -> d.receivable().getStoreId())).collect(Collectors.toSet()));
        Map<Long, String> names = names(Stream.concat(debts.stream().map(d -> d.receivable().getRecognizedBy()),
                paid.stream().map(Payment::getRecordedBy)).collect(Collectors.toSet()));
        Map<Long, List<AttachmentResponse>> debtFiles = attachments.list(OwnerType.RECEIVABLE, debts.stream()
                .filter(d -> d.receivable().getOriginType() == OriginType.OPENING_BALANCE).map(Debt::id).toList());
        Map<Long, List<AttachmentResponse>> paymentFiles = attachments.list(OwnerType.PAYMENT,
                paid.stream().map(Payment::getId).toList());
        Map<Long, Long> corrections = ledger.corrections(byId.keySet());

        return new CustomerDebtsResponse(customer.getId(), customer.getCustomerName(), customer.getPhone(),
                customer.getCreditStatus(), scope, scope == null ? null : storeNames.get(scope),
                ledger.owedByCustomer(List.of(customerId)).getOrDefault(customerId, 0L),
                debts.stream().mapToLong(Debt::remaining).sum(),
                debts.stream().filter(d -> d.overdue(today)).mapToLong(Debt::remaining).sum(),
                debts.stream().map(d -> debtResponse(d, today, corrections, storeNames, names, debtFiles)).toList(),
                paid.stream().map(p -> paymentResponse(p, split.getOrDefault(p.getId(), List.of()), byId, storeNames,
                        names, paymentFiles.getOrDefault(p.getId(), List.of()))).toList());
    }

    @Transactional
    @Override
    public DebtResponse recordOpeningDebt(AuthenticatedUser owner, long customerId, OpeningDebtRequest request) {
        Customer customer = lock(customerId);
        Store store = stores.findById(request.storeId()).filter(s -> s.getStatus() == StoreStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
        receivables.findFirstByCustomerIdAndStoreIdAndOriginTypeAndOriginalAmountAndDueDateOrderByIdAsc(
                        customer.getId(), store.getId(), OriginType.OPENING_BALANCE, request.amount(), request.dueDate())
                .ifPresent(existing -> {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG147", String.valueOf(existing.getId()));
                });

        LocalDateTime now = LocalDateTime.now(clock);
        Receivable debt = receivables.save(Receivable.openingBalance(customer.getId(), store.getId(), request.amount(),
                request.dueDate(), request.note().strip(), owner.id(), now));
        attachments.attach(OwnerType.RECEIVABLE, debt.getId(), request.evidence(), owner.id());
        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("customer_id", customer.getId());
        recorded.put("store_id", store.getId());
        recorded.put("amount", request.amount());
        recorded.put("due_date", request.dueDate().toString());
        auditLog.save(AuditLog.record("RECEIVABLE", debt.getId(), "OPENING_DEBT_RECORDED", owner.id(), null, null,
                recorded, now));

        Debt owed = new Debt(debt, debt.getOriginalAmount());
        return debtResponse(owed, ledger.today(), Map.of(), Map.of(store.getId(), store.getStoreName()),
                names(Set.of(owner.id())), Map.of(debt.getId(), attachments.list(OwnerType.RECEIVABLE, debt.getId())));
    }

    @Transactional(readOnly = true)
    @Override
    public DebtPaymentPreviewResponse previewPayment(AuthenticatedUser collector, long customerId,
                                                     DebtPaymentPreviewRequest request) {
        Store store = collectingStore(collector, request.storeId());
        if (!customers.existsById(customerId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG74");
        }
        List<Debt> eligible = eligible(customerId, store.getId(), request.receivableIds());
        Map<Long, Long> shares = shares(allocation(eligible, request.amount()));
        LocalDate today = ledger.today();
        return new DebtPaymentPreviewResponse(customerId, store.getId(), store.getStoreName(), request.amount(),
                eligible.stream().mapToLong(Debt::remaining).sum(),
                eligible.stream().map(debt -> {
                    long share = shares.getOrDefault(debt.id(), 0L);
                    Receivable r = debt.receivable();
                    return new DebtPaymentPreviewResponse.Line(r.getId(), r.getOriginType(), r.getSalesOrderId(),
                            r.getDueDate(), debt.overdue(today), debt.remaining(), share, debt.remaining() - share);
                }).toList());
    }

    @Transactional
    @Override
    public DebtPaymentResponse recordPayment(AuthenticatedUser collector, long customerId, DebtPaymentRequest request) {
        Store store = collectingStore(collector, request.storeId());
        Long tendered = request.tenderedAmount();
        if (tendered != null && (request.paymentMethod() != PaymentMethod.CASH || tendered < request.amount())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG153");
        }
        String reference = StringUtils.hasText(request.transactionReference())
                ? request.transactionReference().strip() : null;
        if (request.paymentMethod() == PaymentMethod.BANK_TRANSFER && reference == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG154");
        }

        Customer customer = lock(customerId);
        if (reference != null) {
            payments.findByTransactionReference(reference).stream().findFirst().ifPresent(existing -> {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG151", reference, String.valueOf(existing.getId()));
            });
        }
        List<Debt> eligible = eligible(customer.getId(), store.getId(), request.receivableIds());
        List<Allocation> plan = allocation(eligible, request.amount());
        // The allocation and the balances the collector confirmed must both still hold, so a payment sent twice
        // (a double click, a retried request) is refused even when the second one would split the same way.
        Map<Long, Long> owedNow = eligible.stream().collect(Collectors.toMap(Debt::id, Debt::remaining));
        Map<Long, Long> confirmed = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        boolean stale = false;
        for (DebtPaymentRequest.AllocationLine line : request.expectedAllocations()) {
            stale |= !seen.add(line.receivableId())
                    || !line.remainingBefore().equals(owedNow.get(line.receivableId()));
            if (line.amount() > 0) {
                confirmed.put(line.receivableId(), line.amount());
            }
        }
        if (stale || !confirmed.equals(shares(plan))) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG152");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Payment payment = payments.save(Payment.verifiedDebtPayment(request.amount(), tendered,
                request.paymentMethod(), reference, collector.id(), now));
        List<PaymentAllocation> saved = allocations.saveAll(plan.stream()
                .map(a -> PaymentAllocation.of(payment.getId(), a.debt().id(), a.amount())).toList());
        plan.stream().filter(a -> a.amount() == a.debt().remaining()).forEach(a -> a.debt().receivable().settle(now));
        attachments.attach(OwnerType.PAYMENT, payment.getId(), request.evidence(), collector.id());

        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("customer_id", customer.getId());
        recorded.put("store_id", store.getId());
        recorded.put("allocations", plan.stream()
                .map(a -> Map.<String, Object>of("receivable_id", a.debt().id(), "amount", a.amount())).toList());
        auditLog.save(AuditLog.record("PAYMENT", payment.getId(), "DEBT_PAYMENT_RECORDED", collector.id(), null, null,
                recorded, now));

        Map<Long, Debt> after = plan.stream().map(a -> new Debt(a.debt().receivable(), a.debt().remaining() - a.amount()))
                .collect(Collectors.toMap(Debt::id, Function.identity()));
        return paymentResponse(payment, saved, after, Map.of(store.getId(), store.getStoreName()),
                names(Set.of(collector.id())), attachments.list(OwnerType.PAYMENT, payment.getId()));
    }

    /** UC-66 Step 3_Customer pays specific debts: the named debts, or every debt still owed at the store (BR-70). */
    private List<Debt> eligible(long customerId, long storeId, List<Long> receivableIds) {
        List<Debt> owed = ledger.debtsOf(customerId, storeId).stream().filter(debt -> debt.remaining() > 0).toList();
        if (owed.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG149");
        }
        if (receivableIds == null || receivableIds.isEmpty()) {
            return owed;
        }
        Set<Long> named = new LinkedHashSet<>(receivableIds);
        Set<Long> owedIds = owed.stream().map(Debt::id).collect(Collectors.toSet());
        named.stream().filter(id -> !owedIds.contains(id)).findFirst().ifPresent(id -> {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG148", String.valueOf(id));
        });
        return owed.stream().filter(debt -> named.contains(debt.id())).toList();
    }

    /** BR-47: the payment may not be more than the eligible debts still owe; then BR-70 splits it over them. */
    private static List<Allocation> allocation(List<Debt> eligible, long amount) {
        long owed = eligible.stream().mapToLong(Debt::remaining).sum();
        if (amount > owed) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG150", owed);
        }
        return DebtLedger.allocate(eligible, amount);
    }

    private static Map<Long, Long> shares(List<Allocation> plan) {
        return plan.stream().collect(Collectors.toMap(a -> a.debt().id(), Allocation::amount));
    }

    /**
     * UC-66 precondition and Step 1_Owner is the collector: a Branch Manager collects for their own store (BR-03), Owner
     * names the store whose debts are paid (MSG155 when they do not).
     */
    private Store collectingStore(AuthenticatedUser collector, Long requestedStoreId) {
        if (collector.role() != Role.OWNER) {
            if (requestedStoreId != null && !requestedStoreId.equals(collector.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            requestedStoreId = collector.storeId();
        } else if (requestedStoreId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG155");
        }
        return stores.findById(requestedStoreId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
    }

    /** The store a list or detail shows: the user's own, any for Owner and the central-warehouse BM, or every store. */
    private Long scopeOf(AuthenticatedUser user, Long storeId) {
        Store store = storeScope.viewableStore(user, storeId);
        return store == null ? null : store.getId();
    }

    private DebtResponse debtResponse(Debt debt, LocalDate today, Map<Long, Long> corrections,
                                      Map<Long, String> storeNames, Map<Long, String> names,
                                      Map<Long, List<AttachmentResponse>> files) {
        Receivable r = debt.receivable();
        long correction = corrections.getOrDefault(r.getId(), 0L);
        long collected = Math.max(0, r.getOriginalAmount() + correction - debt.remaining());
        DebtStatus status = debt.remaining() == 0 ? DebtStatus.SETTLED
                : collected > 0 ? DebtStatus.PARTIALLY_PAID : DebtStatus.OUTSTANDING;
        return new DebtResponse(r.getId(), r.getOriginType(), r.getCustomerId(), r.getStoreId(),
                storeNames.get(r.getStoreId()), r.getSalesOrderId(), r.getOriginalAmount(), correction, collected,
                debt.remaining(), status, r.getDueDate(), debt.overdue(today), r.getRecognizedAt(),
                names.get(r.getRecognizedBy()), r.getSettledAt(), r.getNote(),
                files.getOrDefault(r.getId(), List.of()));
    }

    private static DebtPaymentResponse paymentResponse(Payment payment, List<PaymentAllocation> split,
                                                       Map<Long, Debt> debts, Map<Long, String> storeNames,
                                                       Map<Long, String> names, List<AttachmentResponse> files) {
        List<DebtPaymentResponse.Allocation> lines = split.stream().filter(a -> debts.containsKey(a.getReceivableId()))
                .map(a -> {
                    Debt debt = debts.get(a.getReceivableId());
                    return new DebtPaymentResponse.Allocation(a.getReceivableId(), debt.receivable().getOriginType(),
                            debt.receivable().getSalesOrderId(), a.getAllocatedAmount(), debt.remaining());
                }).toList();
        // A debt payment is collected at one store for one customer, so any of its debts tells which.
        Receivable first = debts.get(lines.getFirst().receivableId()).receivable();
        Long change = payment.getTenderedAmount() == null ? null : payment.getTenderedAmount() - payment.getAmount();
        return new DebtPaymentResponse(payment.getId(), first.getCustomerId(), first.getStoreId(),
                storeNames.get(first.getStoreId()), payment.getAmount(), payment.getTenderedAmount(), change,
                payment.getPaymentMethod(), payment.getTransactionReference(), payment.getStatus(),
                payment.getRecordedBy(), names.get(payment.getRecordedBy()), payment.getRecordedAt(), lines, files);
    }

    /** Same search as the customer list (UC-26): name, phone without spaces, or customer number. */
    private static boolean matches(Customer customer, String q) {
        if (!StringUtils.hasText(q)) {
            return true;
        }
        String needle = q.strip().toLowerCase(Locale.ROOT);
        String digits = needle.replace(" ", "");
        return customer.getCustomerName().toLowerCase(Locale.ROOT).contains(needle)
                || (customer.getPhone() != null && customer.getPhone().replace(" ", "").contains(digits))
                || (digits.matches("[0-9]{1,18}") && customer.getId() == Long.parseLong(digits));
    }

    private Customer lock(long customerId) {
        return customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG74"));
    }

    private Map<Long, String> storeNames(Collection<Long> storeIds) {
        return stores.findAllById(storeIds).stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
    }

    private Map<Long, String> names(Collection<Long> userIds) {
        // A HashMap, so that looking up a person who is not there (a null id) gives null.
        Map<Long, String> names = new HashMap<>();
        Set<Long> wanted = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (!wanted.isEmpty()) {
            users.findAllById(wanted).forEach(person -> names.put(person.getId(), person.getFullName()));
        }
        return names;
    }
}
