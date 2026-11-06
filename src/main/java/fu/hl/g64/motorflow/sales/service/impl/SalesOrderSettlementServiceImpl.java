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
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.sales.dto.CancelSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.CompleteSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.OrderCreditRequest;
import fu.hl.g64.motorflow.sales.dto.OrderPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.OrderRefundRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.CompletionType;
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
import fu.hl.g64.motorflow.sales.service.SalesOrderSettlementService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final ReceivableRepository receivables;
    private final AuditLogRepository auditLog;
    private final WarehouseTaskRepository tasks;
    private final WarehouseTaskService warehouseTasks;
    private final NotificationService notifications;
    private final AttachmentService attachments;
    private final InventoryPostingService inventory;
    private final OrderCollections collections;
    private final OrderPricing pricing;
    private final PickingGuide guide;
    private final SalesOrderService salesOrders;
    private final TransactionTemplate transactions;
    private final MessageSource messages;
    private final Clock clock;

    // UC-60 steps 3-9
    @Override
    public SettlementResult recordPayment(AuthenticatedUser seller, long orderId, OrderPaymentRequest request) {
        Long paymentId = transactions.execute(status -> {
            SalesOrder order = lockUnfinished(orderId);
            requireSeller(seller, order);
            requireConfirmed(order);
            long total = total(orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId));
            long payable = collections.of(orderId).payable(total);
            if (payable == 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG180");
            }
            if (request.amount() > payable) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG181", money(payable));
            }
            Long tendered = request.tenderedAmount();
            if (tendered != null && (request.paymentMethod() != PaymentMethod.CASH || tendered < request.amount())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG182");
            }
            String reference = reference(request.paymentMethod(), request.transactionReference());
            Payment payment = payments.save(Payment.orderPayment(orderId, request.amount(), tendered,
                    request.paymentMethod(), reference, request.verified(), seller.id(), LocalDateTime.now(clock)));
            if (request.evidence() != null && !request.evidence().isEmpty()) {
                attachments.attach(OwnerType.PAYMENT, payment.getId(), request.evidence(), seller.id());
            }
            return payment.getId();
        });
        return settle(seller, orderId, Objects.requireNonNull(paymentId));
    }

    // UC-60 Step 7_Net collection exceeds the total
    @Override
    public SettlementResult recordRefund(AuthenticatedUser seller, long orderId, OrderRefundRequest request) {
        Long refundId = transactions.execute(status -> {
            SalesOrder order = lockUnfinished(orderId);
            requireSeller(seller, order);
            return saveRefund(orderId, collections.of(orderId), request.amount(), request.paymentMethod(),
                    request.transactionReference(), request.verified(), seller, LocalDateTime.now(clock)).getId();
        });
        return settle(seller, orderId, Objects.requireNonNull(refundId));
    }

    // BR-46
    @Override
    public SettlementResult verifyPayment(AuthenticatedUser seller, long orderId, long paymentId) {
        transactions.executeWithoutResult(status -> {
            SalesOrder order = lockUnfinished(orderId);
            requireSeller(seller, order);
            Payment payment = unverifiedRow(orderId, paymentId);
            if (payment.getPaymentType() == PaymentType.REFUND) {
                OrderCollections.Money money = collections.of(orderId);
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
        SalesOrder order = lockUnfinished(orderId);
        requireSeller(seller, order);
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
        SalesOrder order = lockUnfinished(orderId);
        requireSeller(seller, order);
        requireConfirmed(order);
        requireVersion(request.version(), currentVersion(orderId));
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId);
        long total = total(lines);
        OrderCollections.Money money = collections.of(orderId);
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
        SalesOrder order = lockUnfinished(orderId);
        requireSeller(seller, order);
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
        OrderCollections.Money money = collections.of(orderId);
        requireVerified(money);
        long unpaid = total - money.netCollected();
        if (unpaid < 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG187", money(-unpaid));
        }
        if (unpaid == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG180");
        }
        if (request.creditAmount() != unpaid) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG190", money(unpaid));
        }
        if (request.dueDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG178");
        }
        order.approveCredit(unpaid, seller.id(), LocalDateTime.now(clock));
        completeLocked(seller, order, lines, money, new Credit(customer.getId(), unpaid, request.dueDate()));
        return salesOrders.get(seller, orderId);
    }

    // UC-56
    @Transactional
    @Override
    public SalesOrderDetail cancel(AuthenticatedUser seller, long orderId, CancelSalesOrderRequest request) {
        SalesOrder order = orders.findByIdForUpdate(orderId).orElseThrow(SalesOrderSettlementServiceImpl::notFound);
        requireSeller(seller, order);
        if (order.getStatus() == SalesOrderStatus.CANCELLED) {
            // UC-56 Step 6_Order already completed or cancelled: show the cancellation without repeating it.
            return salesOrders.get(seller, orderId);
        }
        if (order.getStatus() == SalesOrderStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG164");
        }
        OrderCollections.Money money = collections.of(orderId);
        requireVerified(money);
        LocalDateTime now = LocalDateTime.now(clock);
        long held = money.netCollected();
        Long refundId = null;
        CancelSalesOrderRequest.Refund refund = request.refund();
        if (refund != null) {
            Payment saved = saveRefund(orderId, money, refund.amount(), refund.paymentMethod(),
                    refund.transactionReference(), true, seller, now);
            refundId = saved.getId();
            held -= saved.getAmount();
        }
        if (held != 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG185", money(held));
        }
        List<WarehouseTask> picking = tasks.findOpenPickings(List.of(orderId));
        String reason = request.reason().strip();
        order.cancel(seller.id(), reason, now);
        warehouseTasks.cancelForDocument(TaskType.PICKING, orderId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", refundId);
        data.put("refunded", refund == null ? 0 : refund.amount());
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
                SalesOrder order = orders.findByIdForUpdate(orderId).orElseThrow(SalesOrderSettlementServiceImpl::notFound);
                if (order.getStatus() != SalesOrderStatus.CONFIRMED) {
                    return false;
                }
                List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNoAsc(orderId);
                OrderCollections.Money money = collections.of(orderId);
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
        Map<Long, PickingGuide.Part> stock = guide.parts(order.getStoreId(), parts.keySet(), now.toLocalDate());
        String lacking = delivered.stream()
                .filter(line -> line.getDeliveredQty() > stock.get(line.getProductId()).sellableQty())
                .map(line -> "%s cần %d, còn %d".formatted(parts.get(line.getProductId()).getSku(),
                        line.getDeliveredQty(), stock.get(line.getProductId()).sellableQty()))
                .collect(Collectors.joining("; "));
        if (!lacking.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG171", lacking);
        }

        List<Map<String, Object>> issued = delivered.stream().map(line -> {
            List<LotQuantity> lots = inventory.takeOut(new OutPosting(order.getStoreId(), line.getProductId(),
                    StockStatus.AVAILABLE, line.getDeliveredQty(), null, MovementType.SALE_ISSUE,
                    SourceRef.salesOrder(orderId), seller.id(), null));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.getProductId());
            row.put("delivered_qty", line.getDeliveredQty());
            row.put("lots", lots.stream().map(lot -> Map.of("lot_id", lot.lotId(), "quantity", lot.quantity()))
                    .toList());
            return row;
        }).toList();

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

    /** BR-48: a refund of at most what the order holds, less refunds still waiting for verification. */
    private Payment saveRefund(long orderId, OrderCollections.Money money, long amount, PaymentMethod method,
                               String transactionReference, boolean verified, AuthenticatedUser seller,
                               LocalDateTime now) {
        long refundable = money.refundable();
        if (amount > refundable) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG183", money(refundable));
        }
        String reference = reference(method, transactionReference);
        return payments.save(Payment.refund(orderId, amount, method, reference, verified, seller.id(), now));
    }

    /**
     * The trimmed transaction reference: required for a bank transfer (MSG154), and not recorded already on a payment
     * that was not rejected (MSG151, BR-45).
     */
    private String reference(PaymentMethod method, String value) {
        String reference = StringUtils.hasText(value) ? value.strip() : null;
        if (method == PaymentMethod.BANK_TRANSFER && reference == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG154");
        }
        if (reference != null) {
            payments.findByTransactionReference(reference).stream().findFirst().ifPresent(existing -> {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG151", reference,
                        String.valueOf(existing.getId()));
            });
        }
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

    /** The order, locked until the end of the transaction, unless it is completed or cancelled (MSG164). */
    private SalesOrder lockUnfinished(long id) {
        SalesOrder order = orders.findByIdForUpdate(id).orElseThrow(SalesOrderSettlementServiceImpl::notFound);
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
