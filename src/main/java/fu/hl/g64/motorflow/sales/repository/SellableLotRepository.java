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
     * What is sellable of a lot and its balance, as JPQL on {@code l} (lot) and {@code b} (balance): AVAILABLE, more
     * than none, and not expired (BR-30, BR-76) at {@code :today}. Shared by the lot list and the sums so they agree.
     */
    String SELLABLE = """
            l.id = b.lotId and b.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.AVAILABLE
              and b.quantity > 0 and l.status <> fu.hl.g64.motorflow.inventory.entity.enums.LotStatus.EXPIRED
              and (l.expiryDate is null or l.expiryDate >= :today)""";

    /**
     * [lot, quantity] of each sellable lot of the parts in the warehouse, in picking order per part: earliest expiry
     * first, lots without expiry last, then oldest received (FEFO, then FIFO, BR-75). An expired lot is never listed.
     */
    @Query("select l, b.quantity from LotBalance b, Lot l where " + SELLABLE + """

            and b.storeId = :storeId and l.productId in :productIds
            order by l.productId, l.expiryDate asc nulls last, l.receivedDate asc, l.id asc""")
    List<Object[]> findSellableLots(Long storeId, Collection<Long> productIds, LocalDate today);

    /** [storeId, productId, sum of sellable quantity] of the parts in every store asked (GVHD 20/11, BR-33). */
    @Query("select b.storeId, l.productId, sum(b.quantity) from LotBalance b, Lot l where " + SELLABLE + """

            and b.storeId in :storeIds and l.productId in :productIds
            group by b.storeId, l.productId""")
    List<Object[]> sumSellableByStore(Collection<Long> storeIds, Collection<Long> productIds, LocalDate today);
}
