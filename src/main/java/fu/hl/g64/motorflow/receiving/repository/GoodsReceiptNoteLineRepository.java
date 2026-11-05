package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GoodsReceiptNoteLineRepository extends JpaRepository<GoodsReceiptNoteLine, Long> {

    /** Receipts with excess goods on which Owner has not decided yet. */
    @Query("""
            select count(distinct l.goodsReceiptNoteId) from GoodsReceiptNoteLine l
            where l.excessDecision = fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision.AWAITING_OWNER""")
    long countReceiptsAwaitingExcessDecision();
}
