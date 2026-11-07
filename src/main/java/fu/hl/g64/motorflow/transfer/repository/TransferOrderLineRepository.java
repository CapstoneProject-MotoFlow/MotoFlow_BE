package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransferOrderLineRepository extends JpaRepository<TransferOrderLine, Long> {

    List<TransferOrderLine> findByTransferOrderIdOrderByProductId(Long transferOrderId);

    interface ProductQty {
        long getProductId();

        long getQty();
    }

    /**
     * UC-70: planned per part on the transfer orders of a request. The schema has no cancelled status, so all count,
     * except make-up lines, which stand in for goods already planned (BR-62).
     */
    @Query("""
            select l.productId as productId, sum(l.plannedQty) as qty
            from TransferOrderLine l
            join TransferOrder o on o.id = l.transferOrderId
            where o.replenishmentRequestId = :requestId
              and l.makeupForCaseId is null
            group by l.productId
            """)
    List<ProductQty> plannedByProduct(@Param("requestId") long requestId);

    /** BR-60: passed plus damaged per part on the order's confirmed receipts, at the destination and back at the source. */
    interface ReceiptTotals {
        long getProductId();

        long getReceived();

        long getReturned();
    }

    @Query("""
            select l.productId as productId,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
                            then l.passedQty + l.damagedQty else 0 end) as received,
                   sum(case when g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.RETURN_TO_SOURCE
                            then l.passedQty + l.damagedQty else 0 end) as returned
            from GoodsReceiptNoteLine l
            join GoodsReceiptNote g on g.id = l.goodsReceiptNoteId
            where g.transferOrderId = :id
              and g.status = fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus.CONFIRMED
            group by l.productId
            """)
    List<ReceiptTotals> confirmedReceipts(@Param("id") long transferOrderId);

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

    /** Losses linked to an adjustment that may still be applied: they settle nothing yet but are spoken for. */
    @Query("""
            select c.productId as productId, sum(e.quantity) as qty
            from DiscrepancyResolutionEvent e
            join DiscrepancyCase c on c.id = e.discrepancyCaseId
            join AdjustmentRequest a on a.id = e.adjustmentRequestId
            where c.transferOrderId = :id
              and e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.APPROVED_LOSS
              and a.status in (fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.DRAFT,
                               fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.PENDING,
                               fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPROVED)
            group by c.productId
            """)
    List<ProductQty> pendingLoss(@Param("id") long transferOrderId);

    /** BR-66: a loss settles transit goods only once its adjustment has been applied. */
    @Query("""
            select c.productId as productId, sum(e.quantity) as qty
            from DiscrepancyResolutionEvent e
            join DiscrepancyCase c on c.id = e.discrepancyCaseId
            join AdjustmentRequest a on a.id = e.adjustmentRequestId
            where c.transferOrderId = :id
              and e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.APPROVED_LOSS
              and a.status = fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPLIED
            group by c.productId
            """)
    List<ProductQty> appliedLoss(@Param("id") long transferOrderId);
}
