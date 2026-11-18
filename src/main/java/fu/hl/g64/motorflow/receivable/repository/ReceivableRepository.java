package fu.hl.g64.motorflow.receivable.repository;

import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReceivableRepository extends JpaRepository<Receivable, Long> {

    /**
     * Remaining amount in VND of receivable {@code r} (BR-53): the original amount with its applied corrections, less
     * the active rows of verified debt payments allocated to it. Reversed allocation rows stay for history and no longer
     * reduce the receivable (BR-71). Every query below uses this one expression, so every screen shows the same debt.
     */
    String REMAINING = """
            r.originalAmount
                   + coalesce((select sum(l.amountChange) from AdjustmentAmountLine l
                               join AdjustmentRequest q on q.id = l.adjustmentRequestId
                               where l.receivableId = r.id
                                 and q.status = fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPLIED), 0)
                   - coalesce((select sum(a.allocatedAmount) from PaymentAllocation a
                               join Payment p on p.id = a.paymentId
                               where a.receivableId = r.id
                                 and a.reversedAt is null
                                 and p.status = fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus.VERIFIED), 0)
            """;

    /** [receivable, remaining amount in VND] of each unsettled receivable of the customers. */
    @Query("select r, " + REMAINING + """
            from Receivable r
            where r.customerId in :customerIds and r.settledAt is null
            order by r.recognizedAt, r.id""")
    List<Object[]> findUnsettledWithRemaining(Collection<Long> customerIds);

    /** [receivable, remaining amount in VND] of each unsettled receivable at one store, or at every store when null. */
    @Query("select r, " + REMAINING + """
            from Receivable r
            where r.settledAt is null and (:storeId is null or r.storeId = :storeId)
            order by r.recognizedAt, r.id""")
    List<Object[]> findUnsettledWithRemainingAt(Long storeId);

    /**
     * [receivable, remaining amount in VND] of every receivable of the customer, settled ones included, at one store
     * or at every store when {@code storeId} is null.
     */
    @Query("select r, " + REMAINING + """
            from Receivable r
            where r.customerId = :customerId and (:storeId is null or r.storeId = :storeId)
            order by r.recognizedAt, r.id""")
    List<Object[]> findWithRemaining(long customerId, Long storeId);

    /** [receivable, remaining amount in VND] of the receivable an order completed on credit created, if any. */
    @Query("select r, " + REMAINING + """
            from Receivable r
            where r.salesOrderId = :salesOrderId""")
    List<Object[]> findWithRemainingBySalesOrderId(long salesOrderId);

    /** [receivable id, sum of applied corrections of its amount] of the receivables that have any. */
    @Query("""
            select l.receivableId, sum(l.amountChange) from AdjustmentAmountLine l
            join AdjustmentRequest q on q.id = l.adjustmentRequestId
            where l.receivableId in :receivableIds
              and q.status = fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPLIED
            group by l.receivableId""")
    List<Object[]> sumCorrections(Collection<Long> receivableIds);

    /** UC-67 Step 5_Debt already recorded: the same opening debt entered a second time. */
    Optional<Receivable> findFirstByCustomerIdAndStoreIdAndOriginTypeAndOriginalAmountAndDueDateOrderByIdAsc(
            long customerId, long storeId, OriginType originType, long originalAmount, LocalDate dueDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receivable r where r.id in :ids order by r.recognizedAt, r.id")
    List<Receivable> findAllByIdForUpdate(Collection<Long> ids);

    /** [receivable, remaining amount in VND] of the given receivables. */
    @Query("select r, " + REMAINING + """
            from Receivable r
            where r.id in :ids
            order by r.recognizedAt, r.id""")
    List<Object[]> findWithRemainingByIds(Collection<Long> ids);
}
