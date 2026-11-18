package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface GoodsReceiptNoteLineRepository extends JpaRepository<GoodsReceiptNoteLine, Long> {

    /** Receipts with excess goods on which Owner has not decided yet. */
    @Query("""
            select count(distinct l.goodsReceiptNoteId) from GoodsReceiptNoteLine l
            where l.excessDecision = fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision.AWAITING_OWNER""")
    long countReceiptsAwaitingExcessDecision();

    List<GoodsReceiptNoteLine> findByGoodsReceiptNoteIdInOrderByIdAsc(Collection<Long> goodsReceiptNoteIds);

    List<GoodsReceiptNoteLine> findByGoodsReceiptNoteIdOrderByIdAsc(Long goodsReceiptNoteId);

    /** The given receipts that have excess Owner has not decided on yet. */
    @Query("""
            select distinct l.goodsReceiptNoteId from GoodsReceiptNoteLine l
            where l.goodsReceiptNoteId in :goodsReceiptNoteIds
              and l.excessDecision = fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision.AWAITING_OWNER""")
    List<Long> findReceiptsAwaitingExcessDecision(Collection<Long> goodsReceiptNoteIds);

    boolean existsByGoodsReceiptNoteIdAndProductIdAndExcessDecision(Long goodsReceiptNoteId, Long productId,
                                                                    ExcessDecision excessDecision);

    /** The lines of the given receipts with this excess decision, for example those still waiting for Owner. */
    List<GoodsReceiptNoteLine> findByGoodsReceiptNoteIdInAndExcessDecision(Collection<Long> goodsReceiptNoteIds,
                                                                          ExcessDecision excessDecision);

    /** [goodsReceiptNoteId, number of lines] of each receipt. */
    @Query("""
            select l.goodsReceiptNoteId, count(l) from GoodsReceiptNoteLine l
            where l.goodsReceiptNoteId in :goodsReceiptNoteIds
            group by l.goodsReceiptNoteId""")
    List<Object[]> countByReceipt(Collection<Long> goodsReceiptNoteIds);

    /**
     * PM 17/11 Q6: [product id, unit price, confirmed at, purchase order id] of the latest confirmed supplier delivery
     * from this supplier that brought each part (delivered quantity above 0), one row per part that has one.
     */
    @Query(value = """
            SELECT DISTINCT ON (l.product_id) l.product_id, l.unit_price, g.confirmed_at, g.purchase_order_id
              FROM goods_receipt_note_line l
              JOIN goods_receipt_note g ON g.id = l.goods_receipt_note_id
              JOIN purchase_order o ON o.id = g.purchase_order_id
             WHERE g.receipt_type = 'SUPPLIER_DELIVERY' AND g.status = 'CONFIRMED'
               AND o.supplier_id = :supplierId AND l.product_id IN (:productIds)
               AND l.delivered_qty > 0 AND l.unit_price IS NOT NULL
             ORDER BY l.product_id, g.confirmed_at DESC, g.id DESC""", nativeQuery = true)
    List<Object[]> findLastPrices(long supplierId, Collection<Long> productIds);
}
