package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The money an order holds (BR-49): verified payments less verified refunds, each with its applied corrections. Every
 * use case of the order reads it here, so the payment dialog, completion, credit and cancellation agree.
 */
@Component
@RequiredArgsConstructor
public class OrderCollections {

    private final PaymentRepository payments;

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
        return new Money(rows, corrections, paid, refunded, unverifiedPaid, unverifiedRefunded, unverified);
    }

    /**
     * {@code netCollected} is what the store holds of the customer's money. Unverified rows do not count (BR-46) but
     * are kept apart, so nobody records the same money twice while waiting for verification.
     */
    public record Money(List<Payment> rows, Map<Long, Long> corrections, long verifiedPaid, long verifiedRefunded,
                        long unverifiedPaid, long unverifiedRefunded, int unverifiedCount) {

        public long netCollected() {
            return verifiedPaid - verifiedRefunded;
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
