package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;

/** Read-only queries of the lot screens; stock postings go through {@link LotBalanceRepository}. */
public interface LotQueryRepository extends Repository<Lot, Long> {

    /** UC-97: one lot in one warehouse with what it holds there. */
    interface LotStock {
        Lot getLot();

        Product getProduct();

        long getStoreId();

        String getStoreName();

        String getSupplierName();

        int getRemaining();
    }

    /**
     * Groups a lot's balances per warehouse. Stock in transit is left out at the start, so a warehouse the lot has
     * not reached yet has no row at all. The tab is filtered here, not in Java, so that paging counts the right rows.
     */
    String LOTS_BY_STORE = """
            from LotBalance b
            join Lot l on l.id = b.lotId
            join Product p on p.id = l.productId
            join Store s on s.id = b.storeId
            left join Supplier su on su.id = l.supplierId
            where b.stockStatus <> fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.IN_TRANSIT
              and (:storeId is null or b.storeId = :storeId)
              and (:supplierId is null or l.supplierId = :supplierId)
              and (:source is null or l.sourceType = :source)
              and l.receivedDate >= coalesce(:receivedFrom, l.receivedDate)
              and l.receivedDate <= coalesce(:receivedTo, l.receivedDate)
              and (:q is null or cast(l.id as String) = :q or lower(p.sku) like :like or lower(p.partName) like :like
                   or lower(l.manufacturerLotNo) like :like)
              and (:tab in ('IN_STOCK', 'DEPLETED')
                   or (:tab = 'NEAR_EXPIRY' and l.expiryDate between :today and :warnUntil)
                   or (:tab = 'EXPIRED' and (l.expiryDate < :today
                       or l.status = fu.hl.g64.motorflow.inventory.entity.enums.LotStatus.EXPIRED)))
            group by l.id, p.id, s.id, su.id
            having (:tab = 'DEPLETED' and sum(b.quantity) = 0) or (:tab <> 'DEPLETED' and sum(b.quantity) > 0)
            """;

    /** UC-97. Depleted lots newest first; the others earliest expiry first, lots without expiry last (BR-75). */
    @Query(value = "select l as lot, p as product, s.id as storeId, s.storeName as storeName, "
            + "su.supplierName as supplierName, sum(b.quantity) as remaining " + LOTS_BY_STORE + """
            order by case when :tab = 'DEPLETED' then l.receivedDate end desc,
                     case when :tab = 'DEPLETED' then l.id end desc,
                     l.expiryDate asc nulls last, l.receivedDate asc, l.id asc""",
            countQuery = "select count(*) from (select l.id as lotId " + LOTS_BY_STORE + ") t")
    Page<LotStock> searchLots(String tab, Long storeId, Long supplierId, LotSourceType source, LocalDate receivedFrom,
                              LocalDate receivedTo, String q, String like, LocalDate today, LocalDate warnUntil,
                              Pageable pageable);

    /** UC-98: one lot of the issue order and its AVAILABLE stock in the warehouse. */
    interface IssueLine {
        Lot getLot();

        int getAvailable();
    }

    /** UC-98: lots of a part that have been in a warehouse, in picking order (BR-75). */
    @Query("""
            select l as lot,
                   sum(case when b.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.AVAILABLE
                            then b.quantity else 0 end) as available
            from LotBalance b
            join Lot l on l.id = b.lotId
            where l.productId = :productId and b.storeId = :storeId
              and b.stockStatus <> fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.IN_TRANSIT
            group by l.id
            order by l.expiryDate asc nulls last, l.receivedDate asc, l.id asc""")
    List<IssueLine> issueOrder(Long productId, Long storeId, Limit limit);
}
