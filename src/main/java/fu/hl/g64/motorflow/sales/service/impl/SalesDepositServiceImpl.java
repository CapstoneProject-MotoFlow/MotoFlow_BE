package fu.hl.g64.motorflow.sales.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.sales.dto.CancelSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.DepositExpiryRequest;
import fu.hl.g64.motorflow.sales.dto.DepositPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.DepositSplitRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.DepositStatus;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.helper.OrderCollections;
import fu.hl.g64.motorflow.sales.helper.OrderDeposits;
import fu.hl.g64.motorflow.sales.helper.SalesOrderHistory;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.sales.service.SalesDepositService;
import fu.hl.g64.motorflow.sales.service.SalesOrderService;
import fu.hl.g64.motorflow.sales.service.SalesOrderSettlementService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SalesDepositServiceImpl implements SalesDepositService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** The cancellation reason of an order whose deposit expired (PM 21/11). */
    static final String FORFEIT_REASON = "Hết hạn cọc";

    private final SalesOrderRepository orders;
    private final TransactionReferences references;
    private final AttachmentService attachments;
    private final AuditLogRepository auditLog;
    private final OrderCollections collections;
    private final OrderDeposits deposits;
    private final SalesOrderService salesOrders;
    private final SalesOrderSettlementService settlements;
    private final Clock clock;

    // BE-3
    @Transactional
    @Override
    public SettlementResult recordDeposit(AuthenticatedUser seller, long orderId, DepositPaymentRequest request) {
        prepareDeposit(seller, orderId, request.depositExpiresOn(), request.expectedDepositRecorded());
        Long tendered = request.tenderedAmount();
        if (tendered != null && (request.paymentMethod() != PaymentMethod.CASH || tendered < request.amount())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG182");
        }
        String reference = TransactionReferences.clean(request.transactionReference());
        if (request.paymentMethod() == PaymentMethod.BANK_TRANSFER && reference == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG154");
        }
        references.requireUnused(reference);
        LocalDateTime now = LocalDateTime.now(clock);
        Payment payment = references.save(Payment.deposit(orderId, request.amount(), tendered,
                request.paymentMethod(), reference, request.verified(), seller.id(), now));
        if (request.evidence() != null && !request.evidence().isEmpty()) {
            attachments.attach(OwnerType.PAYMENT, payment.getId(), request.evidence(), seller.id());
        }
        return new SettlementResult(salesOrders.get(seller, orderId), payment.getId(), false, null, null);
    }

    @Transactional
    @Override
    public SettlementResult recordSplitDeposit(AuthenticatedUser seller, long orderId, DepositSplitRequest request) {
        long cash = request.cashAmount() == null ? 0 : request.cashAmount();
        long transfer = request.transferAmount() == null ? 0 : request.transferAmount();
        if (cash + transfer == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG333");
        }
        prepareDeposit(seller, orderId, request.depositExpiresOn(), request.expectedDepositRecorded());
        String reference = transfer > 0 ? TransactionReferences.clean(request.transactionReference()) : null;
        if (transfer > 0 && reference == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG154");
        }
        references.requireUnused(reference);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> paymentIds = new ArrayList<>(2);
        if (cash > 0) {
            paymentIds.add(references.save(Payment.deposit(orderId, cash, null, PaymentMethod.CASH, null, true,
                    seller.id(), now)).getId());
        }
        if (transfer > 0) {
            paymentIds.add(references.save(Payment.deposit(orderId, transfer, null, PaymentMethod.BANK_TRANSFER,
                    reference, true, seller.id(), now)).getId());
        }
        long paymentId = paymentIds.getLast();
        if (request.evidence() != null && !request.evidence().isEmpty()) {
            attachments.attach(OwnerType.PAYMENT, paymentId, request.evidence(), seller.id());
        }
        return new SettlementResult(salesOrders.get(seller, orderId), paymentId, false, null, null, List.copyOf(paymentIds));
    }

    private void prepareDeposit(AuthenticatedUser seller, long orderId, LocalDate expiry, long expected) {
        SalesOrder order = lockWaiting(orderId);
        boolean storeSeller = seller.role() == Role.OWNER || (seller.role() == Role.BRANCH_MANAGER
                && Objects.equals(seller.storeId(), order.getStoreId()));
        if (!storeSeller) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireWaiting(order);
        if (order.getPriceType() != PriceType.WHOLESALE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG335");
        }
        // Mạnh 21/11: a deposit belongs to a customer profile, so the store knows whose money it is.
        if (order.getCustomerId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG320");
        }
        OrderCollections.Money money = collections.of(order);
        OrderDeposits.State held = deposits.of(order, money);
        // A rejected deposit leaves the date behind with nothing recorded. That date is not a deposit,
        // so it neither blocks a new one (MSG321) nor freezes the date (MSG323). Money still waiting
        // to be verified does count, so a second deposit keeps the date and the recorded total.
        boolean fresh = held.recorded() == 0;
        if (!fresh) {
            requireNotExpired(order);
        }
        LocalDate today = LocalDate.now(clock);
        if (fresh || order.getDepositExpiresOn() == null) {
            if (expiry == null || !expiry.isAfter(today)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG322");
            }
        } else if (expiry != null && !expiry.equals(order.getDepositExpiresOn())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG323", order.getDepositExpiresOn().format(DAY));
        }
        // BR-45: the same deposit sent twice finds more already recorded.
        if (expected != held.recorded()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG194");
        }
        if (fresh || order.getDepositExpiresOn() == null) {
            order.setDepositExpiry(expiry, LocalDateTime.now(clock));
        }
    }

    // BE-4
    @Transactional
    @Override
    public SalesOrderDetail changeExpiry(AuthenticatedUser manager, long orderId, DepositExpiryRequest request) {
        SalesOrder order = lockWaiting(orderId);
        // The same people who record a deposit: Owner, or the Branch Manager of this store.
        boolean storeSeller = manager.role() == Role.OWNER || (manager.role() == Role.BRANCH_MANAGER
                && Objects.equals(manager.storeId(), order.getStoreId()));
        if (!storeSeller) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireWaiting(order);
        LocalDate old = order.getDepositExpiresOn();
        // A rejected first deposit still leaves the date behind, with nothing verified to extend (MSG324).
        if (old == null || deposits.of(order, collections.of(order)).total() == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG324");
        }
        requireNotExpired(order);
        LocalDate next = request.depositExpiresOn();
        if (!next.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG322");
        }
        if (!next.equals(old)) {
            LocalDateTime now = LocalDateTime.now(clock);
            order.setDepositExpiry(next, now);
            auditLog.save(AuditLog.record(SalesOrderHistory.ENTITY, orderId, SalesOrderHistory.DEPOSIT_EXPIRY_CHANGED,
                    manager.id(), null, Map.of(SalesOrderHistory.DEPOSIT_EXPIRES_ON, old.toString()),
                    Map.of(SalesOrderHistory.DEPOSIT_EXPIRES_ON, next.toString()), now));
        }
        return salesOrders.get(manager, orderId);
    }

    // BE-7
    @Transactional
    @Override
    public SalesOrderDetail forfeit(AuthenticatedUser manager, long orderId) {
        SalesOrder order = lockWaiting(orderId);
        // The same people who change the expiry date: Owner, or the Branch Manager of this store.
        boolean storeSeller = manager.role() == Role.OWNER || (manager.role() == Role.BRANCH_MANAGER
                && Objects.equals(manager.storeId(), order.getStoreId()));
        if (!storeSeller) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireWaiting(order);
        OrderDeposits.State deposit = deposits.of(order, collections.of(order));
        if (deposit.status() == DepositStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG329", order.getDepositExpiresOn().format(DAY));
        }
        if (deposit.status() != DepositStatus.EXPIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG330");
        }
        // The order is cancelled as any waiting order its customer gave up, which keeps the deposit left.
        return settlements.cancel(manager, orderId, new CancelSalesOrderRequest(FORFEIT_REASON, null));
    }

    private SalesOrder lockWaiting(long orderId) {
        return orders.findByIdForUpdate(orderId).filter(found -> !found.isDraft())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG156"));
    }

    /** A deposit belongs to a waiting order only (MSG319). */
    private static void requireWaiting(SalesOrder order) {
        if (!order.isWaitingForStock()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG319", order.getId());
        }
    }

    /** After its last day a deposit takes no more money and keeps its date (MSG321). */
    private void requireNotExpired(SalesOrder order) {
        if (deposits.expired(order)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG321", order.getDepositExpiresOn().format(DAY));
        }
    }
}
