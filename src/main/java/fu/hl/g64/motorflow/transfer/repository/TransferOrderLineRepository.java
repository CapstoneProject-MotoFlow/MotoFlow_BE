package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TransferOrderLineRepository extends JpaRepository<TransferOrderLine, Long> {

    List<TransferOrderLine> findByTransferOrderIdOrderByProductId(Long transferOrderId);

    /** [transferOrderId, number of lines] of each order. */
    @Query("""
            select l.transferOrderId, count(l) from TransferOrderLine l
            where l.transferOrderId in :orderIds
            group by l.transferOrderId""")
    List<Object[]> countLinesByOrder(@Param("orderIds") Collection<Long> orderIds);

    interface ProductQty {
        long getProductId();

        long getQty();
    }

    /**
     * UC-70: planned per part on the transfer orders of a request. Orders already dispatched count what they actually
     * dispatched, the others count their plan, so a short dispatch frees the unsent remainder for a new order or
     * purchasing. The schema has no cancelled status, so all orders count, except make-up lines, which stand in for
     * goods already planned (BR-62).
     */
    @Query("""
            select l.productId as productId, sum(coalesce(l.dispatchedQty, l.plannedQty)) as qty
            from TransferOrderLine l
            join TransferOrder o on o.id = l.transferOrderId
            where o.replenishmentRequestId = :requestId
              and l.makeupForCaseId is null
            group by l.productId
            """)
    List<ProductQty> plannedByProduct(@Param("requestId") long requestId);

    /**
     * BR-60: passed plus damaged per part on the order's confirmed receipts, at the destination and back at the source.
     * {@code excess} is the part of {@code received} above what those receipts expected (BR-68); it never was in transit.
     */
    interface ReceiptTotals {
        long getProductId();

        long getReceived();

        long getReturned();

        long getExcess();

        long getDamaged();

        long getLegacyExcess();

        long getPendingExcess();

        long getWrongItem();
    }

    /** PM 23/11: what the order's discrepancy events say per part, for {@code TransferReconciliation}. */
    interface EventTotals {
        long getProductId();

        long getWrongAwaitingOwner();

        long getWrongConfirmed();

        long getNoReplacement();

        long getNoReplacementDone();

        long getLatePending();

        long getMiscounts();
    }

    @Query("""
            select c.productId as productId,
                   sum(case when e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.RECEIVED_AS_OTHER_PRODUCT
                            and e.status = fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus.AWAITING_OWNER
                            then e.quantity else 0 end) as wrongAwaitingOwner,
                   sum(case when e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.RECEIVED_AS_OTHER_PRODUCT
                            and e.status = fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus.CONFIRMED
                            then e.quantity else 0 end) as wrongConfirmed,
                   sum(case when e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.NO_REPLACEMENT
                            then e.quantity else 0 end) as noReplacement,
                   sum(case when e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.NO_REPLACEMENT
                            and e.confirmedAt is not null then e.quantity else 0 end) as noReplacementDone,
                   sum(case when e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.LATE_DELIVERY_RECEIVED
                            and e.confirmedAt is null then e.quantity else 0 end) as latePending,
                   sum(case when e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.RECEIVER_MISCOUNT
                            then 1 else 0 end) as miscounts
            from DiscrepancyResolutionEvent e
            join DiscrepancyCase c on c.id = e.discrepancyCaseId
            where c.transferOrderId = :id
            group by c.productId
            """)
    List<EventTotals> eventTotals(@Param("id") long transferOrderId);

    @Query("""
            select l.productId as productId,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
                            then l.passedQty + l.damagedQty else 0 end) as received,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.RETURN_TO_SOURCE
                            then l.passedQty + l.damagedQty else 0 end) as returned,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
                            then l.excessQty else 0 end) as excess,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
                            then l.damagedQty else 0 end) as damaged,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
                            and l.excessDecision = fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision.NOT_APPLICABLE
                            then l.excessQty else 0 end) as legacyExcess,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
                            and l.excessDecision = fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision.AWAITING_OWNER
                            then l.excessQty else 0 end) as pendingExcess,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
                            then l.wrongItemQty else 0 end) as wrongItem
            from GoodsReceiptNoteLine l
            join GoodsReceiptNote g on g.id = l.goodsReceiptNoteId
            where g.transferOrderId = :id
              and g.status = fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus.CONFIRMED
            group by l.productId
            """)
    List<ReceiptTotals> confirmedReceipts(@Param("id") long transferOrderId);

    @Query("""
            select m.productId as productId, -sum(m.quantityChange) as qty from StockMovement m
            where m.transferOrderId = :id and m.movementType = fu.hl.g64.motorflow.inventory.entity.enums.MovementType.ADJUSTMENT
              and m.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.IN_TRANSIT
              and m.quantityChange < 0
            group by m.productId
            """)
    List<ProductQty> acknowledgedShortages(@Param("id") long transferOrderId);

    /** What open receipts of the order, at the destination or back at the source, still expect per part. */
    @Query("""
            select l.productId as productId, sum(l.documentQty) as qty
            from GoodsReceiptNoteLine l
            join GoodsReceiptNote g on g.id = l.goodsReceiptNoteId
            where g.transferOrderId = :id
              and g.status <> fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus.CONFIRMED
            group by l.productId
            """)
    List<ProductQty> openReceiptQty(@Param("id") long transferOrderId);

    /** What open returns to the source still expect per part. */
    @Query("""
            select l.productId as productId, sum(l.documentQty) as qty
            from GoodsReceiptNoteLine l
            join GoodsReceiptNote g on g.id = l.goodsReceiptNoteId
            where g.transferOrderId = :id
              and g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.RETURN_TO_SOURCE
              and g.status <> fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus.CONFIRMED
            group by l.productId
            """)
    List<ProductQty> openReturnQty(@Param("id") long transferOrderId);

    /** UC-76: quantity per part that coordinators decided to send back and no confirmed return has settled yet. */
    @Query("""
            select c.productId as productId, sum(e.quantity) as qty
            from DiscrepancyResolutionEvent e
            join DiscrepancyCase c on c.id = e.discrepancyCaseId
            where c.transferOrderId = :id
              and e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.RECEIVED_BACK_AT_SOURCE
              and e.confirmedAt is null
            group by c.productId
            """)
    List<ProductQty> pendingReturnDecisions(@Param("id") long transferOrderId);

    /** Transit losses of the order whose adjustment may still be applied: they settle nothing yet but are spoken for. */
    @Query("""
            select l.productId as productId, -sum(l.quantityChange) as qty
            from AdjustmentStockLine l
            join AdjustmentRequest a on a.id = l.adjustmentRequestId
            join TransferOrder o on o.id = a.transferOrderId
            where a.transferOrderId = :id and l.storeId = o.destStoreId
              and l.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.IN_TRANSIT
              and l.quantityChange < 0
              and a.status in (fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.DRAFT,
                               fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.PENDING,
                               fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPROVED)
            group by l.productId
            """)
    List<ProductQty> pendingLoss(@Param("id") long transferOrderId);

    /**
     * BR-66: a loss settles transit goods only once its adjustment has been applied. PM 23/11: what the order's
     * STOCK_LOSS adjustments write off the destination's transit, linked to a loss decision (before 23/11) or not
     * (UC-83 reported directly), as that is what left transit.
     */
    @Query("""
            select l.productId as productId, -sum(l.quantityChange) as qty
            from AdjustmentStockLine l
            join AdjustmentRequest a on a.id = l.adjustmentRequestId
            join TransferOrder o on o.id = a.transferOrderId
            where a.transferOrderId = :id and l.storeId = o.destStoreId
              and l.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.IN_TRANSIT
              and l.quantityChange < 0
              and a.status = fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPLIED
            group by l.productId
            """)
    List<ProductQty> appliedLoss(@Param("id") long transferOrderId);
}
