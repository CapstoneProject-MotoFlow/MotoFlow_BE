package fu.hl.g64.motorflow.discrepancy.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface DiscrepancyResolutionEventRepository extends JpaRepository<DiscrepancyResolutionEvent, Long> {

    /** What was done on the cases, in the order it happened. */
    List<DiscrepancyResolutionEvent> findByDiscrepancyCaseIdInOrderByOccurredAtAscIdAsc(Collection<Long> caseIds);

    /** The events of one type a receipt is linked to, for example the replacements a replacement delivery brings. */
    List<DiscrepancyResolutionEvent> findByGoodsReceiptNoteIdAndResolutionTypeOrderByIdAsc(Long goodsReceiptNoteId,
                                                                                         ResolutionType type);

    /**
     * The given receipts that are replacement deliveries (UC-106 Step 1_Supplier replacement): they settle
     * discrepancy cases instead of the PO.
     */
    @Query("""
            select distinct e.goodsReceiptNoteId from DiscrepancyResolutionEvent e
            where e.resolutionType = fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType.REPLACEMENT_RECEIVED
              and e.goodsReceiptNoteId in :receiptIds""")
    List<Long> findReplacementReceiptIds(Collection<Long> receiptIds);
}
