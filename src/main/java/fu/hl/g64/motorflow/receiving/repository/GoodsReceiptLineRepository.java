package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, Long> {

    /** Receipts with excess goods on which Owner has not decided yet. */
    @Query("""
            select count(distinct l.goodsReceiptId) from GoodsReceiptLine l
            where l.excessDecision = fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision.AWAITING_OWNER""")
    long countReceiptsAwaitingExcessDecision();
}
