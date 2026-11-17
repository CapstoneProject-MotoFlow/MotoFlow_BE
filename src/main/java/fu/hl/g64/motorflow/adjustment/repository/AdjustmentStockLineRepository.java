package fu.hl.g64.motorflow.adjustment.repository;

import fu.hl.g64.motorflow.adjustment.entity.AdjustmentStockLine;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface AdjustmentStockLineRepository extends JpaRepository<AdjustmentStockLine, Long> {

    List<AdjustmentStockLine> findByAdjustmentRequestIdOrderByIdAsc(Long adjustmentRequestId);

    List<AdjustmentStockLine> findByAdjustmentRequestIdIn(Collection<Long> adjustmentRequestIds);

    /** Statuses whose requests still hold stock they may take: submitted and not applied, rejected or withdrawn. */
    String OPEN = """
            q.status in (fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.PENDING,
                         fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPROVED)""";

    /**
     * Units of the listed parts at the warehouse that other open requests would take, per part, status and lot (lot
     * null: the line names no lot), so two requests cannot write off the same goods.
     */
    interface OpenDeduction {
        long getProductId();

        StockStatus getStatus();

        Long getLotId();

        long getQuantity();
    }

    @Query("select l.productId as productId, l.stockStatus as status, l.lotId as lotId, "
            + "-sum(l.quantityChange) as quantity from AdjustmentStockLine l "
            + "join AdjustmentRequest q on q.id = l.adjustmentRequestId where " + OPEN + """
              and q.id <> :exceptRequestId and l.quantityChange < 0
              and l.storeId = :storeId and l.productId in :productIds
            group by l.productId, l.stockStatus, l.lotId""")
    List<OpenDeduction> findOpenDeductions(long storeId, Collection<Long> productIds, long exceptRequestId);

    /**
     * [productId, units] of the listed parts other open requests write off the transit of the transfer order (UC-83
     * Step 1_Transit loss).
     */
    @Query("select l.productId, -sum(l.quantityChange) from AdjustmentStockLine l "
            + "join AdjustmentRequest q on q.id = l.adjustmentRequestId where " + OPEN + """
              and q.id <> :exceptRequestId and q.transferOrderId = :transferOrderId and l.productId in :productIds
              and l.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.IN_TRANSIT
            group by l.productId""")
    List<Object[]> findOpenTransitWriteOffs(long transferOrderId, Collection<Long> productIds, long exceptRequestId);

    /** Units of the part other open requests write off from the held goods of the supplier delivery. */
    @Query("select coalesce(-sum(l.quantityChange), 0) from AdjustmentStockLine l "
            + "join AdjustmentRequest q on q.id = l.adjustmentRequestId where " + OPEN + """
              and q.id <> :exceptRequestId and q.goodsReceiptNoteId = :receiptId and l.productId = :productId
              and l.quantityChange < 0""")
    long sumOpenHeldWriteOffs(long receiptId, long productId, long exceptRequestId);
}
