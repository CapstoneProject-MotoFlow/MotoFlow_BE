package fu.hl.g64.motorflow.sales.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.sales.dto.CancelSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.DepositExpiryRequest;
import fu.hl.g64.motorflow.sales.dto.DepositPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.sales.entity.SalesDepositExpiryChange;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.DepositStatus;
import fu.hl.g64.motorflow.sales.helper.OrderCollections;
import fu.hl.g64.motorflow.sales.helper.OrderDeposits;
import fu.hl.g64.motorflow.sales.repository.SalesDepositExpiryChangeRepository;
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

@Service
@RequiredArgsConstructor
public class SalesDepositServiceImpl implements SalesDepositService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** The cancellation reason of an order whose deposit expired (PM 21/11). */
    static final String FORFEIT_REASON = "Hết hạn cọc";

    private final SalesOrderRepository orders;
    private final TransactionReferences references;
    private final AttachmentService attachments;
    private final SalesDepositExpiryChangeRepository expiryChanges;
    private final OrderCollections collections;
    private final OrderDeposits deposits;
    private final SalesOrderService salesOrders;
    private final SalesOrderSettlementService settlements;
    private final Clock clock;

    // BE-3
    @Transactional
    @Override
    public SettlementResult recordDeposit(AuthenticatedUser seller, long orderId, DepositPaymentRequest request) {
        SalesOrder order = lockWaiting(orderId);
        boolean storeSeller = seller.role() == Role.OWNER || (seller.role() == Role.BRANCH_MANAGER
                && Objects.equals(seller.storeId(), order.getStoreId()));
        if (!storeSeller) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireWaiting(order);
        // Mạnh 21/11: a deposit belongs to a customer profile, so the store knows whose money it is.
        if (order.getCustomerId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG320");
        }
        requireNotExpired(order);
        LocalDate today = LocalDate.now(clock);
        LocalDate expiry = request.depositExpiresOn();
        if (order.getDepositExpiresOn() == null) {
            if (expiry == null || !expiry.isAfter(today)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG322");
            }
        } else if (expiry != null && !expiry.equals(order.getDepositExpiresOn())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG323", order.getDepositExpiresOn().format(DAY));
        }
        OrderCollections.Money money = collections.of(orderId);
        // BR-45: the same deposit sent twice finds more already recorded.
        if (request.expectedDepositRecorded() != deposits.of(order, money).recorded()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG194");
        }
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
        if (order.getDepositExpiresOn() == null) {
            order.setDepositExpiry(expiry, now);
        }
        Payment payment = references.save(Payment.deposit(orderId, request.amount(), tendered,
                request.paymentMethod(), reference, request.verified(), seller.id(), now));
        if (request.evidence() != null && !request.evidence().isEmpty()) {
            attachments.attach(OwnerType.PAYMENT, payment.getId(), request.evidence(), seller.id());
        }
        return new SettlementResult(salesOrders.get(seller, orderId), payment.getId(), false, null, null);
    }

    // BE-4
    @Transactional
    @Override
    public SalesOrderDetail changeExpiry(AuthenticatedUser manager, long orderId, DepositExpiryRequest request) {
        SalesOrder order = lockWaiting(orderId);
        // PM 21/11: only the store's Branch Manager (Owner and Staff are refused, E12 waits for PM).
        if (manager.role() != Role.BRANCH_MANAGER || !Objects.equals(manager.storeId(), order.getStoreId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireWaiting(order);
        LocalDate old = order.getDepositExpiresOn();
        if (old == null) {
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
            expiryChanges.save(SalesDepositExpiryChange.of(orderId, old, next, manager.id(), now));
        }
        return salesOrders.get(manager, orderId);
    }

    // BE-7
    @Transactional
    @Override
    public SalesOrderDetail forfeit(AuthenticatedUser manager, long orderId) {
        SalesOrder order = lockWaiting(orderId);
        // PM 21/11: the store's Branch Manager declares the deposit lost, as they change its expiry date.
        if (manager.role() != Role.BRANCH_MANAGER || !Objects.equals(manager.storeId(), order.getStoreId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireWaiting(order);
        OrderDeposits.State deposit = deposits.of(order, collections.of(orderId));
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
