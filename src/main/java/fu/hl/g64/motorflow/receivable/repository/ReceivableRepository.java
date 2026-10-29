package fu.hl.g64.motorflow.receivable.repository;

import fu.hl.g64.motorflow.receivable.entity.Receivable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ReceivableRepository extends JpaRepository<Receivable, Long> {

    /**
     * [receivable, remaining amount in VND] of each unsettled receivable of the customers: the original amount with its
     * applied corrections, less the verified debt payments allocated to it with their corrections. Only verified
     * payments count.
     * shortcut: a correction of a payment split over several receivables counts against each of them; settle how
     * such a correction is shared when debt payments are built.
     */
    @Query("""
            select r, r.originalAmount
                   + coalesce((select sum(l.amountChange) from AdjustmentAmountLine l
                               join AdjustmentRequest q on q.id = l.adjustmentRequestId
                               where l.receivableId = r.id
                                 and q.status = fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPLIED), 0)
                   - coalesce((select sum(a.allocatedAmount) from PaymentAllocation a
                               join Payment p on p.id = a.paymentId
                               where a.receivableId = r.id
                                 and p.status = fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus.VERIFIED), 0)
                   - coalesce((select sum(l.amountChange) from AdjustmentAmountLine l
                               join AdjustmentRequest q on q.id = l.adjustmentRequestId
                               join Payment p on p.id = l.paymentId
                               join PaymentAllocation a on a.paymentId = p.id
                               where a.receivableId = r.id
                                 and p.status = fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus.VERIFIED
                                 and q.status = fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPLIED), 0)
            from Receivable r
            where r.customerId in :customerIds and r.settledAt is null
            order by r.recognizedAt, r.id""")
    List<Object[]> findUnsettledWithRemaining(Collection<Long> customerIds);
}
