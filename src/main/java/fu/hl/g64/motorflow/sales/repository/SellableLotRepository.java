package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.inventory.entity.LotBalance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Read-only views of the goods a store can hand over: AVAILABLE lots that have not expired (BR-30, BR-76). Kept apart
 * from the inventory repositories, which lock rows for postings.
 */
public interface SellableLotRepository extends Repository<LotBalance, Long> {

    /**
     * [lot, quantity] of each sellable lot of the parts in the warehouse, in picking order per part: earliest expiry
     * first, lots without expiry last, then oldest received (FEFO, then FIFO, BR-75). An expired lot is never listed.
     */
    @Query("""
            select l, b.quantity from LotBalance b, Lot l
            where l.id = b.lotId and b.storeId = :storeId and l.productId in :productIds
              and b.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.AVAILABLE and b.quantity > 0
              and l.status <> fu.hl.g64.motorflow.inventory.entity.enums.LotStatus.EXPIRED
              and (l.expiryDate is null or l.expiryDate >= :today)
            order by l.productId, l.expiryDate asc nulls last, l.receivedDate asc, l.id asc""")
    List<Object[]> findSellableLots(Long storeId, Collection<Long> productIds, LocalDate today);
}
