package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.sales.entity.SalesDepositExpiryChange;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.DepositStatus;
import fu.hl.g64.motorflow.sales.repository.SalesDepositApplicationRepository;
import fu.hl.g64.motorflow.sales.repository.SalesDepositExpiryChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * PM 21/11: the deposit of a waiting order. The deposit is DEPOSIT payments on the waiting order itself; releases take
 * parts of it into the orders they open (sales_deposit_application) and the last release gives any surplus back as a
 * REFUND on the waiting order. Only verified money counts (BR-46).
 */
@Component
@RequiredArgsConstructor
public class OrderDeposits {

    private final SalesDepositApplicationRepository applications;
    private final SalesDepositExpiryChangeRepository expiryChanges;
    private final Clock clock;

    /** The deposit of {@code order}, read from the money it holds. */
    public State of(SalesOrder order, OrderCollections.Money money) {
        long total = 0;
        long unverified = 0;
        for (Payment row : money.rows()) {
            if (row.getPaymentType() != PaymentType.DEPOSIT) {
                continue;
            }
            if (row.getStatus() == PaymentStatus.VERIFIED) {
                total += money.amountOf(row);
            } else if (row.getStatus() == PaymentStatus.UNVERIFIED) {
                unverified += money.amountOf(row);
            }
        }
        boolean waiting = order.isWaitingForStock();
        long applied = total == 0 ? 0 : applications.sumAppliedFrom(order.getId());
        // While the order waits, every refund on it gives deposit back; once it is released whole, the deposit pays it.
        long refunded = waiting ? money.verifiedRefunded() : 0;
        long remaining = waiting && order.getDepositForfeitedAt() == null ? Math.max(0, total - applied - refunded) : 0;
        LocalDate expiresOn = order.getDepositExpiresOn();
        DepositStatus status;
        if (order.getDepositForfeitedAt() != null) {
            status = DepositStatus.FORFEITED;
        } else if (total == 0) {
            status = DepositStatus.NONE;
        } else if (remaining == 0) {
            status = DepositStatus.SETTLED;
        } else if (expiresOn != null && LocalDate.now(clock).isAfter(expiresOn)) {
            status = DepositStatus.EXPIRED;
        } else {
            status = DepositStatus.ACTIVE;
        }
        List<SalesDepositExpiryChange> changes = expiresOn == null ? List.of()
                : expiryChanges.findBySalesOrderIdOrderByChangedAtAscIdAsc(order.getId());
        return new State(total, unverified, applied, refunded, remaining, expiresOn, status, changes);
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
