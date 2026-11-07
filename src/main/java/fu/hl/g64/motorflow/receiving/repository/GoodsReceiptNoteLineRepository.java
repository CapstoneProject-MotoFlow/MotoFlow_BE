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
}
