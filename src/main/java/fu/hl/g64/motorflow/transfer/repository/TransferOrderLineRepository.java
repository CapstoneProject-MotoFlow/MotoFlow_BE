package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransferOrderLineRepository extends JpaRepository<TransferOrderLine, Long> {

    interface ProductQty {
        long getProductId();

        long getQty();
    }

    /** UC-70: planned per part on the transfer orders of a request. The schema has no cancelled status, so all count. */
    @Query("""
            select l.productId as productId, sum(l.plannedQty) as qty
            from TransferOrderLine l
            join TransferOrder o on o.id = l.transferOrderId
            where o.replenishmentRequestId = :requestId
            group by l.productId
            """)
    List<ProductQty> plannedByProduct(@Param("requestId") long requestId);

    /** UC-70: passed at the destination per part. Goods returned to the source also carry the transfer order id. */
    @Query("""
            select l.productId as productId, sum(l.passedQty) as qty
            from GoodsReceiptNoteLine l
            join GoodsReceiptNote g on g.id = l.goodsReceiptNoteId
            join TransferOrder o on o.id = g.transferOrderId
            where o.replenishmentRequestId = :requestId
              and g.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
              and g.status = fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus.CONFIRMED
            group by l.productId
            """)
    List<ProductQty> fulfilledByProduct(@Param("requestId") long requestId);
}
