package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.dto.ExpiringLotRow;
import fu.hl.g64.motorflow.inventory.entity.LotBalance;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LotBalanceRepository extends JpaRepository<LotBalance, Long> {

    /** Creates the empty row if it is missing; safe when two transactions do it at once. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into lot_balance (lot_id, store_id, stock_status, quantity, updated_at)
            values (:lotId, :storeId, :status, 0, :now)
            on conflict (lot_id, store_id, stock_status) do nothing""", nativeQuery = true)
    void insertIfMissing(Long lotId, Long storeId, String status, LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from LotBalance b where b.lotId = :lotId and b.storeId = :storeId and b.stockStatus = :status")
    Optional<LotBalance> findForUpdate(Long lotId, Long storeId, StockStatus status);

    /**
     * Lots of a part in one warehouse and status that still hold stock, in picking order: earliest expiry first
     * (lots without expiry last), then oldest received (FEFO, then FIFO). Row-locked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from LotBalance b, Lot l
            where l.id = b.lotId and l.productId = :productId
              and b.storeId = :storeId and b.stockStatus = :status and b.quantity > 0
            order by l.expiryDate asc nulls last, l.receivedDate asc, l.id asc""")
    List<LotBalance> findPickableForUpdate(Long storeId, Long productId, StockStatus status);

    @Query("select coalesce(sum(b.quantity), 0) from LotBalance b where b.lotId = :lotId")
    long sumQuantityOfLot(Long lotId);

    /**
     * [storeId, productId, expired quantity, quantity expiring by {@code warnUntil}] of AVAILABLE stock, for lots with an
     * expiry date (BR-76). A lot already marked EXPIRED counts as expired.
     */
    @Query("""
            select b.storeId, l.productId,
                   sum(case when l.expiryDate < :today or l.status = fu.hl.g64.motorflow.inventory.entity.enums.LotStatus.EXPIRED
                            then b.quantity else 0 end),
                   sum(case when l.expiryDate >= :today and l.expiryDate <= :warnUntil
                             and l.status <> fu.hl.g64.motorflow.inventory.entity.enums.LotStatus.EXPIRED
                            then b.quantity else 0 end)
            from LotBalance b, Lot l
            where l.id = b.lotId and b.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.AVAILABLE
              and b.quantity > 0 and l.expiryDate is not null
              and b.storeId in :storeIds and l.productId in :productIds
            group by b.storeId, l.productId""")
    List<Object[]> sumExpiryByStoreAndProduct(Collection<Long> storeIds, Collection<Long> productIds,
                                              LocalDate today, LocalDate warnUntil);

    /** UC-98: a lot's balances in one warehouse, by status. */
    List<LotBalance> findByLotIdAndStoreIdOrderByStockStatusAsc(Long lotId, Long storeId);

    /** Every lot balance of a part in one warehouse that still holds stock. */
    @Query("""
            select b from LotBalance b, Lot l
            where l.id = b.lotId and l.productId = :productId and b.storeId = :storeId and b.quantity > 0
            order by l.expiryDate asc nulls last, l.receivedDate asc, l.id asc""")
    List<LotBalance> findHeldByStoreAndProduct(Long storeId, Long productId);

    /** JOB-03: available stock of lots expiring between {@code today} and {@code warnUntil}, by lot and warehouse. */
    @Query("""
            select new fu.hl.g64.motorflow.inventory.dto.ExpiringLotRow(b.lotId, b.storeId, s.storeName, p.sku,
                   l.expiryDate, sum(b.quantity))
            from LotBalance b join Lot l on l.id = b.lotId join Product p on p.id = l.productId
                 join Store s on s.id = b.storeId
            where b.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.AVAILABLE and b.quantity > 0
              and l.status <> fu.hl.g64.motorflow.inventory.entity.enums.LotStatus.EXPIRED
              and l.expiryDate >= :today and l.expiryDate <= :warnUntil
            group by b.lotId, b.storeId, s.storeName, p.sku, l.expiryDate
            order by b.storeId, l.expiryDate, b.lotId""")
    List<ExpiringLotRow> findNearExpiry(LocalDate today, LocalDate warnUntil);

    /** JOB-03: available stock of the given lots, by lot and warehouse. */
    @Query("""
            select new fu.hl.g64.motorflow.inventory.dto.ExpiringLotRow(b.lotId, b.storeId, s.storeName, p.sku,
                   l.expiryDate, sum(b.quantity))
            from LotBalance b join Lot l on l.id = b.lotId join Product p on p.id = l.productId
                 join Store s on s.id = b.storeId
            where b.stockStatus = fu.hl.g64.motorflow.inventory.entity.enums.StockStatus.AVAILABLE and b.quantity > 0
              and b.lotId in :lotIds
            group by b.lotId, b.storeId, s.storeName, p.sku, l.expiryDate
            order by b.storeId, b.lotId""")
    List<ExpiringLotRow> findAvailableOf(Collection<Long> lotIds);
}
