package fu.hl.g64.motorflow.discrepancy.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface DiscrepancyResolutionEventRepository extends JpaRepository<DiscrepancyResolutionEvent, Long> {

    /** What was done on the cases, in the order it happened. */
    List<DiscrepancyResolutionEvent> findByDiscrepancyCaseIdInOrderByOccurredAtAscIdAsc(Collection<Long> caseIds);

    /** The events of one type a receipt is linked to, for example the replacements a replacement delivery brings. */
    List<DiscrepancyResolutionEvent> findByGoodsReceiptNoteIdAndResolutionTypeOrderByIdAsc(Long goodsReceiptNoteId,
                                                                                         ResolutionType type);

    /** UC-76: the return decisions of one part of a transfer order that no return receipt has settled, oldest first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from DiscrepancyResolutionEvent e join DiscrepancyCase c on c.id = e.discrepancyCaseId
            where c.transferOrderId = :transferOrderId and c.productId = :productId
              and e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.RECEIVED_BACK_AT_SOURCE
              and e.confirmedAt is null
            order by e.occurredAt, e.id""")
    List<DiscrepancyResolutionEvent> findPendingReturnDecisionsForUpdate(long transferOrderId, long productId);

    /** PM 23/11: one event, locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DiscrepancyResolutionEvent e where e.id = :id")
    java.util.Optional<DiscrepancyResolutionEvent> findByIdForUpdate(long id);

    /** GVHD 24/11: the transfer phiếu nhận hàng giao sai awaiting the source's BM, newest first. */
    @Query("""
            select e from DiscrepancyResolutionEvent e join DiscrepancyCase c on c.id = e.discrepancyCaseId
            where c.transferOrderId is not null
              and e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.RECEIVED_AS_OTHER_PRODUCT
              and e.status = fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus.AWAITING_SOURCE
            order by e.occurredAt desc, e.id desc""")
    List<DiscrepancyResolutionEvent> findPendingTransferSwaps(org.springframework.data.domain.Pageable page);

    /** PM 23/11: the events of one transfer case still pending (decisions, phiếu nhận hàng giao sai), locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from DiscrepancyResolutionEvent e where e.discrepancyCaseId = :caseId
              and (e.confirmedAt is null
                   or e.status = fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus.AWAITING_SOURCE)
            order by e.occurredAt, e.id""")
    List<DiscrepancyResolutionEvent> findOpenByCaseForUpdate(long caseId);

    /** PM 23/11: pending decisions of one type for one part of a transfer order, oldest first, locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from DiscrepancyResolutionEvent e join DiscrepancyCase c on c.id = e.discrepancyCaseId
            where c.transferOrderId = :transferOrderId and c.productId = :productId
              and e.resolutionType = :type and e.confirmedAt is null
            order by e.occurredAt, e.id""")
    List<DiscrepancyResolutionEvent> findPendingForUpdate(long transferOrderId, long productId, ResolutionType type);

    /** PM 23/11: pending NO_REPLACEMENT decisions of a transfer order, locked, for its closing. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from DiscrepancyResolutionEvent e join DiscrepancyCase c on c.id = e.discrepancyCaseId
            where c.transferOrderId = :transferOrderId
              and e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.NO_REPLACEMENT
              and e.confirmedAt is null
            order by c.productId, e.occurredAt, e.id""")
    List<DiscrepancyResolutionEvent> findPendingNoReplacementForUpdate(long transferOrderId);

    /**
     * The given receipts that are replacement deliveries (UC-106 Step 1_Supplier replacement): they settle
     * discrepancy cases instead of the PO.
     */
    @Query("""
            select distinct e.goodsReceiptNoteId from DiscrepancyResolutionEvent e
            where e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.REPLACEMENT_RECEIVED
              and e.goodsReceiptNoteId in :receiptIds""")
    List<Long> findReplacementReceiptIds(Collection<Long> receiptIds);

    /** A transfer loss linked to the adjustment request (UC-76): the request cannot be thrown away. */
    boolean existsByAdjustmentRequestId(Long adjustmentRequestId);
}
