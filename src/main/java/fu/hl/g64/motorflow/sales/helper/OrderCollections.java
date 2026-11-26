package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The money an order holds (BR-49): verified payments less verified refunds, each with its applied corrections, and
 * the deposit its release put into it (PM 21/11). Every use case of the order reads it here, so the payment dialog,
 * completion, credit and cancellation agree. The deposit payments of a waiting order are its deposit, read by
 * {@link OrderDeposits}, not money paid for the order.
 */
@Component
@RequiredArgsConstructor
public class OrderCollections {

    private final PaymentRepository payments;
    private final SalesOrderRepository orders;

    /** The order's payments and refunds, oldest first, with what they add up to. */
    public Money of(long orderId) {
        List<Payment> rows = payments.findBySalesOrderIdOrderByRecordedAtAscIdAsc(orderId);
        Map<Long, Long> corrections = rows.isEmpty() ? Map.of()
                : payments.sumCorrections(rows.stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).longValue()));
        long paid = 0;
        long refunded = 0;
        long unverifiedPaid = 0;
        long unverifiedRefunded = 0;
        int unverified = 0;
        for (Payment row : rows) {
            if (row.getPaymentType() == PaymentType.DEPOSIT || row.getPaymentType() == PaymentType.DEPOSIT_REFUND) {
                if (row.getStatus() == PaymentStatus.UNVERIFIED) {
                    unverified++;
                }
                continue;
            }
            long amount = row.getAmount() + corrections.getOrDefault(row.getId(), 0L);
            boolean refund = row.getPaymentType() == PaymentType.REFUND;
            if (row.getStatus() == PaymentStatus.VERIFIED) {
                if (refund) {
                    refunded += amount;
                } else {
                    paid += amount;
                }
            } else if (row.getStatus() == PaymentStatus.UNVERIFIED) {
                unverified++;
                if (refund) {
                    unverifiedRefunded += amount;
                } else {
                    unverifiedPaid += amount;
                }
            }
        }
        long fromDeposit = orders.depositAppliedTo(orderId);
        return new Money(rows, corrections, paid, refunded, unverifiedPaid, unverifiedRefunded, unverified,
                fromDeposit);
    }

    /**
     * {@code netCollected} is what the store holds of the customer's money. Unverified rows do not count (BR-46) but
     * are kept apart, so nobody records the same money twice while waiting for verification. {@code unverifiedCount}
     * counts deposits too, since an unverified deposit also waits for someone. {@code depositApplied} is the deposit of
     * the waiting order this one was released from, which pays for it like a verified payment (BE-6).
     */
    public record Money(List<Payment> rows, Map<Long, Long> corrections, long verifiedPaid, long verifiedRefunded,
                        long unverifiedPaid, long unverifiedRefunded, int unverifiedCount, long depositApplied) {

        public long netCollected() {
            return verifiedPaid + depositApplied - verifiedRefunded;
        }

        /** BR-47: what may still be recorded as paid for an order of this total. */
        public long payable(long total) {
            return Math.max(0, total - netCollected() - unverifiedPaid);
        }

        /** BR-48: what may still be refunded. */
        public long refundable() {
            return Math.max(0, netCollected() - unverifiedRefunded);
        }

        /** The amount of a row with its applied corrections. */
        public long amountOf(Payment row) {
            return row.getAmount() + corrections.getOrDefault(row.getId(), 0L);
        }
    }
}
