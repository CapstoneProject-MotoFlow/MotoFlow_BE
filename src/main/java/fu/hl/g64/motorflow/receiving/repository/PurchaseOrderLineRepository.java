package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {

    /** [purchaseOrderId, number of lines, agreed value in VND] of each request. */
    @Query("""
            select l.purchaseOrderId, count(l), sum(l.orderedQty * l.purchasePrice) from PurchaseOrderLine l
            where l.purchaseOrderId in :requestIds
            group by l.purchaseOrderId""")
    List<Object[]> summarizeByRequest(Collection<Long> requestIds);

    List<PurchaseOrderLine> findByPurchaseOrderIdOrderByIdAsc(Long purchaseOrderId);

    /** Removes every line of one purchase order in one statement (draft replace or discard). */
    @Modifying(flushAutomatically = true)
    @Query("delete from PurchaseOrderLine l where l.purchaseOrderId = :purchaseOrderId")
    void deleteByPurchaseOrderId(Long purchaseOrderId);
}
