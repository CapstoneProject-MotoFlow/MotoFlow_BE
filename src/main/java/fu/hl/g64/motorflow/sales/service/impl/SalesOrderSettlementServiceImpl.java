package fu.hl.g64.motorflow.sales.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
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
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.sales.dto.CancelSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.CompleteSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.OrderCreditRequest;
import fu.hl.g64.motorflow.sales.dto.OrderPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.OrderSplitPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.OrderRefundRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.CompletionType;
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
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.sales.service.SalesOrderService;
import fu.hl.g64.motorflow.sales.service.SalesOrderSettlementService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalesOrderSettlementServiceImpl implements SalesOrderSettlementService {

    /** notification.ref_type of a sales order, so that opening the notification opens it. */
    private static final String REF_TYPE = "SALES_ORDER";
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

    private final SalesOrderRepository orders;
    private final SalesOrderLineRepository orderLines;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final PaymentRepository payments;
    private final TransactionReferences references;
    private final ReceivableRepository receivables;
    private final AuditLogRepository auditLog;
    private final WarehouseTaskRepository tasks;
    private final WarehouseTaskService warehouseTasks;
    private final NotificationService notifications;
    private final AttachmentService attachments;
    private final InventoryPostingService inventory;
    private final OrderCollections collections;
    private final OrderDeposits deposits;
    private final OrderPricing pricing;
    private final PickingGuide guide;
    private final SalesOrderService salesOrders;
    private final TransactionTemplate transactions;
    private final MessageSource messages;
    private final WaitingOrderReadiness readiness;
    private final Clock clock;

    // UC-60 steps 3-9
    @Override
    public SettlementResult recordPayment(AuthenticatedUser seller, long orderId, OrderPaymentRequest request) {
        Long paymentId = transactions.execute(status -> {
            SalesOrder order = lockForSeller(seller, orderId);
            requireNotWaiting(order);
            requireConfirmed(order);
            long total = total(orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId));
            long payable = collections.of(order).payable(total);
            if (payable == 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG180");
            }
            // BR-45: the same payment sent twice (a double click, a retried request) finds less still payable.
            if (request.expectedPayable() != payable) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG194");
            }
            if (request.amount() > payable) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG181", money(payable));
            }
            Long tendered = request.tenderedAmount();
            if (tendered != null && (request.paymentMethod() != PaymentMethod.CASH || tendered < request.amount())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG182");
            }
            String reference = reference(request.paymentMethod(), request.transactionReference());
            Payment payment = references.save(Payment.orderPayment(orderId, request.amount(), tendered,
                    request.paymentMethod(), reference, request.verified(), seller.id(), LocalDateTime.now(clock)));
            if (request.evidence() != null && !request.evidence().isEmpty()) {
                attachments.attach(OwnerType.PAYMENT, payment.getId(), request.evidence(), seller.id());
            }
            return payment.getId();
        });
        return settle(seller, orderId, Objects.requireNonNull(paymentId));
    }

    // UC-60, góp ý UI 21/11
    @Override
    public SettlementResult recordSplitPayment(AuthenticatedUser seller, long orderId,
                                               OrderSplitPaymentRequest request) {
        Long paymentId = transactions.execute(status -> {
            SalesOrder order = lockForSeller(seller, orderId);
            requireNotWaiting(order);
            requireConfirmed(order);
            long total = total(orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId));
            long payable = collections.of(order).payable(total);
            if (payable == 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG180");
            }
            if (request.expectedPayable() != payable) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG194");
            }
            long cash = request.cash();
            long transfer = request.transfer();
            if (cash + transfer == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG333");
            }
            if (cash + transfer > payable) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG181", money(payable));
            }
            Long tendered = request.tenderedAmount();
            if (tendered != null && (cash == 0 || tendered < cash)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG182");
            }
            String reference = transfer > 0
                    ? reference(PaymentMethod.BANK_TRANSFER, request.transactionReference()) : null;
            LocalDateTime now = LocalDateTime.now(clock);
            Payment last = null;
            if (cash > 0) {
                last = references.save(Payment.orderPayment(orderId, cash, tendered, PaymentMethod.CASH, null, true,
                        seller.id(), now));
            }
            if (transfer > 0) {
                last = references.save(Payment.orderPayment(orderId, transfer, null, PaymentMethod.BANK_TRANSFER,
                        reference, true, seller.id(), now));
            }
            if (request.evidence() != null && !request.evidence().isEmpty()) {
                attachments.attach(OwnerType.PAYMENT, last.getId(), request.evidence(), seller.id());
            }
            return last.getId();
        });
        return settle(seller, orderId, Objects.requireNonNull(paymentId));
    }

    // UC-60 Step 7_Net collection exceeds the total
    @Override
    public SettlementResult recordRefund(AuthenticatedUser seller, long orderId, OrderRefundRequest request) {
        Long refundId = transactions.execute(status -> {
            SalesOrder order = lockForSeller(seller, orderId);
            requireNotWaiting(order);
            OrderCollections.Money money = collections.of(order);
            // BR-45: the same refund sent twice finds less still refundable.
            if (request.expectedRefundable() != money.refundable()) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG194");
            }
            return saveRefund(orderId, money, request.amount(), request.paymentMethod(),
                    request.transactionReference(), request.verified(), seller, LocalDateTime.now(clock)).getId();
        });
        return settle(seller, orderId, Objects.requireNonNull(refundId));
    }

    // BR-46
    @Override
    public SettlementResult verifyPayment(AuthenticatedUser seller, long orderId, long paymentId) {
        transactions.executeWithoutResult(status -> {
            SalesOrder order = lockForSeller(seller, orderId);
            Payment payment = unverifiedRow(orderId, paymentId);
            if (payment.getPaymentType() == PaymentType.REFUND) {
                OrderCollections.Money money = collections.of(order);
                if (money.amountOf(payment) > money.netCollected()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG183",
                            money(Math.max(0, money.netCollected())));
                }
            }
            payment.verify(seller.id(), LocalDateTime.now(clock));
        });
        return settle(seller, orderId, paymentId);
    }

    // UC-56 Step 4_Unverified transaction
    @Transactional
    @Override
    public SalesOrderDetail rejectPayment(AuthenticatedUser seller, long orderId, long paymentId, String reason) {
        SalesOrder order = lockForSeller(seller, orderId);
        Payment payment = unverifiedRow(orderId, paymentId);
        LocalDateTime now = LocalDateTime.now(clock);
        String why = reason.strip();
        payment.reject(why, now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment_id", paymentId);
        data.put("payment_type", payment.getPaymentType().name());
        data.put("amount", payment.getAmount());
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, orderId, SalesOrderHistory.PAYMENT_REJECTED,
                seller.id(), why, null, data, now));
        return salesOrders.get(seller, orderId);
    }

    // UC-60 Step 2_Already paid after failed completion
    @Transactional
    @Override
    public SalesOrderDetail complete(AuthenticatedUser seller, long orderId, CompleteSalesOrderRequest request) {
        SalesOrder order = lockForSeller(seller, orderId);
        requireConfirmed(order);
        requireVersion(request.version(), currentVersion(orderId));
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId);
        long total = total(lines);
        OrderCollections.Money money = collections.of(order);
        requireVerified(money);
        long unpaid = total - money.netCollected();
        if (unpaid > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG186", money(unpaid));
        }
        if (unpaid < 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG187", money(-unpaid));
        }
        completeLocked(seller, order, lines, money, null);
        return salesOrders.get(seller, orderId);
    }

    // UC-61
    @Transactional
    @Override
    public SalesOrderDetail recordCredit(AuthenticatedUser seller, long orderId, OrderCreditRequest request) {
        SalesOrder order = lockForSeller(seller, orderId);
        requireConfirmed(order);
        requireVersion(request.version(), currentVersion(orderId));
        if (order.getCustomerId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG188");
        }
        // BR-44: the lock makes a revocation committed before this point visible, and one after it wait.
        Customer customer = customers.findByIdForUpdate(order.getCustomerId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG74"));
        if (customer.getCreditStatus() != CreditStatus.ELIGIBLE) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG189");
        }
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId);
        long total = total(lines);
        OrderCollections.Money money = collections.of(order);
        requireVerified(money);
        long unpaid = total - money.netCollected();
        if (unpaid < 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG187", money(-unpaid));
        }
        if (unpaid == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG180");
        }
        // Plan 23/11 (3b): a prepayment in this request is recorded first, verified; only payments from before it had
        // to be verified (requireVerified above).
        long cash = request.cash();
        long transfer = request.transfer();
        Long tendered = request.tenderedAmount();
        if (tendered != null && (cash == 0 || tendered < cash)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG182");
        }
        long credit = unpaid - cash - transfer;
        if (credit <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG347", money(unpaid));
        }
        if (request.creditAmount() != credit) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG190", money(credit));
        }
        if (request.dueDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG178");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (cash > 0) {
            references.save(Payment.orderPayment(orderId, cash, tendered, PaymentMethod.CASH, null, true, seller.id(),
                    now));
        }
        if (transfer > 0) {
            references.save(Payment.orderPayment(orderId, transfer, null, PaymentMethod.BANK_TRANSFER,
                    reference(PaymentMethod.BANK_TRANSFER, request.transactionReference()), true, seller.id(), now));
        }
        if (cash + transfer > 0) {
            money = collections.of(order);
        }
        order.approveCredit(credit, seller.id(), now);
        completeLocked(seller, order, lines, money, new Credit(customer.getId(), credit, request.dueDate()));
        return salesOrders.get(seller, orderId);
    }

    // UC-56
    @Transactional
    @Override
    public SalesOrderDetail cancel(AuthenticatedUser seller, long orderId, CancelSalesOrderRequest request) {
        SalesOrder order = orders.findByIdForUpdate(orderId).filter(found -> !found.isDraft())
                .orElseThrow(SalesOrderSettlementServiceImpl::notFound);
        requireSeller(seller, order);
        if (order.getStatus() == SalesOrderStatus.CANCELLED) {
            // UC-56 Step 6_Order already completed or cancelled: show the cancellation without repeating it.
            return salesOrders.get(seller, orderId);
        }
        if (order.getStatus() == SalesOrderStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG164");
        }
        OrderCollections.Money money = collections.of(order);
        requireVerified(money);
        LocalDateTime now = LocalDateTime.now(clock);
        long held = money.netCollected();
        OrderDeposits.State deposit = order.isWaitingForStock() ? deposits.of(order, money) : null;
        long depositLeft = deposit == null ? 0 : deposit.remaining();
        Long refundId = null;
        CancelSalesOrderRequest.Refund refund = request.refund();
        if (order.isWaitingForStock()) {
            // A refund here is the deposit still held. The ordinary refund path cannot see it (MSG308).
            if (refund != null) {
                if (refund.amount() != depositLeft) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG331", money(depositLeft));
                }
                if (depositLeft > 0) {
                    Payment saved = saveDepositRefund(orderId, refund.amount(), refund.paymentMethod(),
                            refund.transactionReference(), seller, now);
                    refundId = saved.getId();
                    depositLeft = 0;
                }
            } else if (deposit != null && depositLeft > 0 && deposit.status() == DepositStatus.EXPIRED
                    && !storeManager(seller, order)) {
                // Forfeiting an expired deposit by cancelling is for the Owner or this store's Branch Manager.
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
        } else if (refund != null) {
            Payment saved = saveRefund(orderId, money, refund.amount(), refund.paymentMethod(),
                    refund.transactionReference(), true, seller, now);
            refundId = saved.getId();
            held -= saved.getAmount();
        }
        if (held != 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG185", money(held));
        }
        // What releases already put into other orders stays there. What is left is kept only when this cancel
        // does not hand it back.
        long forfeited = depositLeft;
        if (forfeited > 0) {
            order.forfeitDeposit(forfeited, seller.id(), now);
        }
        List<WarehouseTask> picking = tasks.findOpenPickings(List.of(orderId));
        String reason = request.reason().strip();
        // GVHD 24/11: what the order held may now make a waiting order ready.
        readiness.storeChanged(order.getStoreId());
        order.cancel(seller.id(), reason, now);
        warehouseTasks.cancelForDocument(TaskType.PICKING, orderId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", refundId);
        data.put("refunded", refund == null ? 0 : refund.amount());
        data.put("deposit_forfeited", forfeited);
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, orderId, SalesOrderHistory.CANCELLED, seller.id(),
                reason, null, data, now));
        picking.stream().map(WarehouseTask::getAssignedTo).filter(picker -> picker != seller.id()).distinct()
                .forEach(picker -> notifications.notify(picker, "SALES_ORDER_CANCELLED",
                        "Đơn bán #%d đã bị hủy, không cần lấy hàng nữa. Lý do: %s".formatted(orderId, reason),
                        REF_TYPE, orderId));
        return salesOrders.get(seller, orderId);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** The order after money changed, completed if it is now exactly paid with nothing waiting for verification. */
    private SettlementResult settle(AuthenticatedUser seller, long orderId, long paymentId) {
        boolean completed = false;
        String code = null;
        String message = null;
        try {
            completed = Boolean.TRUE.equals(transactions.execute(status -> {
                SalesOrder order = orders.findByIdForUpdate(orderId).filter(found -> !found.isDraft())
                .orElseThrow(SalesOrderSettlementServiceImpl::notFound);
                if (order.getStatus() != SalesOrderStatus.CONFIRMED) {
                    return false;
                }
                List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId);
                OrderCollections.Money money = collections.of(order);
                if (money.unverifiedCount() > 0 || money.netCollected() != total(lines)) {
                    return false;
                }
                completeLocked(seller, order, lines, money, null);
                return true;
            }));
        } catch (BusinessException blocked) {
            // UC-60 Step 7_Completion conditions fail: the payment stays, nothing is posted.
            code = blocked.getCode();
            message = messages.getMessage(code, blocked.getArgs(), code, VIETNAMESE);
        } catch (RuntimeException failed) {
            // A lock timeout, a deadlock or a bug: the money is saved already, so say so instead of an error page,
            // which would invite the cashier to collect it again. POST /complete retries.
            log.error("Completing sales order {} after payment {} failed", orderId, paymentId, failed);
            code = "MSG195";
            message = messages.getMessage(code, null, code, VIETNAMESE);
        }
        return new SettlementResult(salesOrders.get(seller, orderId), paymentId, completed, code, message);
    }

    /**
     * UC-60 steps 7-8, UC-61 steps 6-8: re-checks the confirmed version against the threshold and the unexpired stock
     * in effect now, takes the delivered goods out (BR-31, BR-75, BR-76), creates the receivable of a credit sale and
     * completes the order once (BR-38). The order row is locked by the caller.
     */
    private void completeLocked(AuthenticatedUser seller, SalesOrder order, List<SalesOrderLine> lines,
                                OrderCollections.Money money, Credit credit) {
        LocalDateTime now = LocalDateTime.now(clock);
        long orderId = order.getId();
        Totals totals = OrderPricing.totals(lines.stream().map(line -> new Priced(line.getDeliveredQty(),
                line.getReferencePrice(), line.getActualUnitPrice())).toList());
        OrderPricing.checkThreshold(order.getDiscountRate(), totals, pricing.discountThreshold(now));

        List<SalesOrderLine> delivered = lines.stream().filter(line -> line.getDeliveredQty() > 0)
                .sorted(Comparator.comparing(SalesOrderLine::getProductId)).toList();
        Map<Long, Product> parts = products.findAllById(delivered.stream().map(SalesOrderLine::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        // The order's own holding is its own (GVHD 20/11): only other open orders' holds are left out.
        Map<Long, PickingGuide.Part> stock = guide.parts(order.getStoreId(), parts.keySet(), now.toLocalDate(),
                orderId);
        String lacking = delivered.stream()
                .filter(line -> line.getDeliveredQty() > stock.get(line.getProductId()).sellableQty())
                .map(line -> "%s cần %d, còn %d".formatted(parts.get(line.getProductId()).getSku(),
                        line.getDeliveredQty(), stock.get(line.getProductId()).sellableQty()))
                .collect(Collectors.joining("; "));
        if (!lacking.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG171", lacking);
        }

        List<List<LotQuantity>> taken = inventory.takeOutAll(delivered.stream()
                .map(line -> new OutPosting(order.getStoreId(), line.getProductId(), StockStatus.AVAILABLE,
                        line.getDeliveredQty(), null, MovementType.SALE_ISSUE, SourceRef.salesOrder(orderId),
                        seller.id(), null))
                .toList());
        List<Map<String, Object>> issued = new ArrayList<>();
        for (int i = 0; i < delivered.size(); i++) {
            SalesOrderLine line = delivered.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.getProductId());
            row.put("delivered_qty", line.getDeliveredQty());
            row.put("lots", taken.get(i).stream().map(lot -> Map.of("lot_id", lot.lotId(), "quantity", lot.quantity()))
                    .toList());
            issued.add(row);
        }

        Long receivableId = null;
        if (credit != null) {
            receivableId = receivables.save(Receivable.creditSale(credit.customerId(), order.getStoreId(), orderId,
                    credit.amount(), credit.dueDate(), seller.id(), now)).getId();
        }
        order.complete(credit == null ? CompletionType.PAID : CompletionType.CREDIT, seller.id(), now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", currentVersion(orderId));
        data.put("completion_type", order.getCompletionType().name());
        data.put("total", totals.total());
        data.put("net_collected", money.netCollected());
        data.put("receivable_id", receivableId);
        data.put("credit_amount", credit == null ? null : credit.amount());
        data.put("due_date", credit == null ? null : credit.dueDate().toString());
        data.put("lines", issued);
        auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, orderId, SalesOrderHistory.COMPLETED, seller.id(),
                null, null, data, now));
    }

    /** The deposit a waiting order still holds, handed back in full when the seller cancels and chooses to return it. */
    private Payment saveDepositRefund(long orderId, long amount, PaymentMethod method, String transactionReference,
                                      AuthenticatedUser seller, LocalDateTime now) {
        String reference = reference(method, transactionReference);
        return references.save(Payment.depositRefund(orderId, amount, method, reference, seller.id(), now));
    }

    /** Owner, or the store's Branch Manager: the people who may declare a deposit lost. */
    private static boolean storeManager(AuthenticatedUser user, SalesOrder order) {
        return user.role() == Role.OWNER
                || (user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId()));
    }

    /** BR-48: a refund of at most what the order holds, less refunds still waiting for verification. */
    private Payment saveRefund(long orderId, OrderCollections.Money money, long amount, PaymentMethod method,
                               String transactionReference, boolean verified, AuthenticatedUser seller,
                               LocalDateTime now) {
        long refundable = money.refundable();
        if (amount > refundable) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG183", money(refundable));
        }
        String reference = reference(method, transactionReference);
        return references.save(Payment.refund(orderId, amount, method, reference, verified, seller.id(), now));
    }

    /**
     * The trimmed transaction reference: required for a bank transfer (MSG154), and not recorded already on a payment
     * that was not rejected (MSG151, BR-45).
     */
    private String reference(PaymentMethod method, String value) {
        String reference = TransactionReferences.clean(value);
        if (method == PaymentMethod.BANK_TRANSFER && reference == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG154");
        }
        references.requireUnused(reference);
        return reference;
    }

    /** A payment or refund of this order (MSG191) that nobody has verified or rejected yet (MSG192). */
    private Payment unverifiedRow(long orderId, long paymentId) {
        Payment payment = payments.findById(paymentId).filter(row -> Objects.equals(row.getSalesOrderId(), orderId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG191"));
        if (!payment.isUnverified()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG192");
        }
        return payment;
    }

    /**
     * The order, locked until the end of the transaction, for its seller (BR-03), unless it is completed or cancelled
     * (MSG164). The seller is checked first, so another store learns nothing of the order's state.
     */
    private SalesOrder lockForSeller(AuthenticatedUser seller, long id) {
        SalesOrder order = orders.findByIdForUpdate(id).filter(found -> !found.isDraft())
                .orElseThrow(SalesOrderSettlementServiceImpl::notFound);
        requireSeller(seller, order);
        if (!order.isUnfinished()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG164");
        }
        return order;
    }

    /** Owner, or the Branch Manager of the order's store (BR-03, BR-46). */
    private static void requireSeller(AuthenticatedUser user, SalesOrder order) {
        boolean seller = user.role() == Role.OWNER
                || (user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId()));
        if (!seller) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
    }

    /**
     * GVHD 20/11, PM 21/11: a waiting order takes no money but its deposit (POST /deposits), and gives deposit back
     * only through its last release (MSG308).
     */
    private static void requireNotWaiting(SalesOrder order) {
        if (order.isWaitingForStock()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG308");
        }
    }

    /** UC-60, UC-61 preconditions: the current version is confirmed (MSG179). */
    private static void requireConfirmed(SalesOrder order) {
        if (order.getStatus() != SalesOrderStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG179");
        }
    }

    /** Nothing waits for verification, so the money cannot change after the order is closed (MSG184). */
    private static void requireVerified(OrderCollections.Money money) {
        if (money.unverifiedCount() > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG184");
        }
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

    /** BR-37: delivered quantity × actual unit price over the lines of a confirmed order. */
    private static long total(List<SalesOrderLine> lines) {
        return lines.stream().mapToLong(line -> (long) (line.getDeliveredQty() == null ? 0 : line.getDeliveredQty())
                * line.getActualUnitPrice()).sum();
    }

    private static String money(long amount) {
        return NumberFormat.getIntegerInstance(VIETNAMESE).format(amount);
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG156");
    }

    /** UC-61 step 4: what goes on credit and until when. */
    private record Credit(long customerId, long amount, LocalDate dueDate) {
    }
}
