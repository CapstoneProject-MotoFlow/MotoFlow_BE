package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.sales.entity.SalesDepositExpiryChange;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.DepositStatus;
import fu.hl.g64.motorflow.sales.repository.SalesDepositExpiryChangeRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * PM 21/11: the deposit of a waiting order. The deposit is DEPOSIT payments on the waiting order itself; releases take
 * parts of it into the orders they open (their deposit_applied_amount) and the last release gives any surplus back as
 * a DEPOSIT_REFUND on the waiting order. Only verified money counts (BR-46). What is left when the order is cancelled
 * or its deposit expires is kept by the store (FORFEITED).
 */
@Component
@RequiredArgsConstructor
public class OrderDeposits {

    private final SalesOrderRepository orders;
    private final SalesDepositExpiryChangeRepository expiryChanges;
    private final Clock clock;

    /** The deposit of {@code order}, read from the money it holds. */
    public State of(SalesOrder order, OrderCollections.Money money) {
        long total = 0;
        long unverified = 0;
        long refunded = 0;
        for (Payment row : money.rows()) {
            if (row.getPaymentType() == PaymentType.DEPOSIT_REFUND && row.getStatus() == PaymentStatus.VERIFIED) {
                refunded += money.amountOf(row);
            }
            if (row.getPaymentType() != PaymentType.DEPOSIT) {
                continue;
            }
            if (row.getStatus() == PaymentStatus.VERIFIED) {
                total += money.amountOf(row);
            } else if (row.getStatus() == PaymentStatus.UNVERIFIED) {
                unverified += money.amountOf(row);
            }
        }
        long applied = total == 0 ? 0 : orders.sumDepositAppliedFrom(order.getId());
        boolean forfeited = order.getDepositForfeitedAt() != null;
        long remaining = forfeited ? 0 : Math.max(0, total - applied - refunded);
        LocalDate expiresOn = order.getDepositExpiresOn();
        DepositStatus status = status(forfeited, total, remaining, expiresOn, LocalDate.now(clock));
        List<SalesDepositExpiryChange> changes = expiresOn == null ? List.of()
                : expiryChanges.findBySalesOrderIdOrderByChangedAtAscIdAsc(order.getId());
        return new State(total, unverified, applied, refunded, remaining, expiresOn, status, changes);
    }

    /**
     * One rule for the detail, the list and its filter: lost, none verified, all used or given back, past its last
     * day, else active.
     */
    public static DepositStatus status(boolean forfeited, long total, long remaining, LocalDate expiresOn,
                                       LocalDate today) {
        if (forfeited) {
            return DepositStatus.FORFEITED;
        }
        if (total == 0) {
            return DepositStatus.NONE;
        }
        if (remaining == 0) {
            return DepositStatus.SETTLED;
        }
        if (expiresOn != null && today.isAfter(expiresOn)) {
            return DepositStatus.EXPIRED;
        }
        return DepositStatus.ACTIVE;
    }

    /**
     * True when today is after the date stored on the order. A rejected deposit can leave that date with no verified
     * money; callers treat that as no deposit, not as expired.
     */
    public boolean expired(SalesOrder order) {
        return order.getDepositExpiresOn() != null && LocalDate.now(clock).isAfter(order.getDepositExpiresOn());
    }

    /**
     * {@code total} and {@code unverified}: verified and still unverified DEPOSIT payments; {@code applied} what
     * releases took into their orders; {@code refunded} what went back to the customer; {@code remaining} what the
     * waiting order still holds.
     */
    public record State(long total, long unverified, long applied, long refunded, long remaining,
                        LocalDate expiresOn, DepositStatus status, List<SalesDepositExpiryChange> changes) {

        /** What the seller has recorded so far, verified or not: the request echoes it back (BR-45, MSG194). */
        public long recorded() {
            return total + unverified;
        }
    }
}
