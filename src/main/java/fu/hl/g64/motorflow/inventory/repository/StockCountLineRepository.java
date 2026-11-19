package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import org.springframework.data.jpa.repository.JpaRepository;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface StockCountLineRepository extends JpaRepository<StockCountLine, Long> {

    /** The count's lines by part, then expiry (lines without a lot last), then id. */
    List<StockCountLine> findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(Long stockCountId);

    /**
     * SC-1 (PM 19/11): [part id, count id] of the given parts already on a count of the warehouse in one of these
     * statuses, by count then part.
     */
    @Query("""
            select distinct l.productId, c.id from StockCountLine l, StockCount c
            where c.id = l.stockCountId and c.storeId = :storeId and c.status in :statuses
              and l.productId in :productIds
            order by c.id, l.productId""")
    List<Object[]> findPartsOnCounts(Long storeId, Collection<StockCountStatus> statuses,
                                     Collection<Long> productIds);
}
